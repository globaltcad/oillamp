package dev.oillamp;

import java.util.Optional;

import sprouts.Tuple;

/// The `[network]` section of the configuration: which outbound connections the egress proxy
/// allows. [NetworkPolicyUtil#decide] applies it.
///
/// It lives in `oillamp.toml`, outside the agent directory, so the agent cannot change it.
record NetworkPolicy(Decision defaultDecision, Tuple<Rule> rules,
                            boolean logAllowed, boolean consoleDenied) {

    /// The default policy: allow the internet, except Eden AI's global endpoint, private networks
    /// and the host's own loopback. Users add allow rules for internal services above the deny
    /// rules.
    public static NetworkPolicy shippedDefault() {
        return new NetworkPolicy(Decision.ALLOW,
                Tuple.of(Rule.class, edenAiOnlyInTheEu(), blockPrivateRanges()), true, true);
    }

    public static final String EDEN_AI_RULE_LABEL = "Eden AI only through its EU endpoint";

    /// Refuses Eden AI's global endpoint, so that Eden AI is only reached through
    /// `api.eu.edenai.run`. The harnesses are set up to use that endpoint already; this rule
    /// covers anything that ignores their settings, and says why in the refusal.
    public static Rule edenAiOnlyInTheEu() {
        return new Rule(EDEN_AI_RULE_LABEL, Decision.DENY,
                Tuple.of(HostPattern.class, HostPattern.parse("api.edenai.run")),
                Tuple.of(PortRange.class), Tuple.of(Cidr.class));
    }

    public static final String DEFAULT_DENY_RULE_LABEL = "block private, internal and loopback ranges";

    public static Rule blockPrivateRanges() {
        Tuple<Cidr> ranges = Tuple.of(Cidr.class);
        for (String text : new String[] {
                "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
                "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8",
                // "::" is the unspecified address. Linux connects to it as this machine.
                "::1/128", "::/128", "fc00::/7", "fe80::/10" })
            ranges = ranges.add(Cidr.parse(text).orElseThrow());
        return new Rule(DEFAULT_DENY_RULE_LABEL, Decision.DENY,
                Tuple.of(HostPattern.class), Tuple.of(PortRange.class), ranges);
    }

    /// This policy, with the networks this machine is on added to the rule that refuses private
    /// ranges, where the lamp still has that rule.
    ///
    /// A network at home or in an office is often not in a private range: IPv6 hands out public
    /// addresses, and a server may have a public IPv4 address. The machine and the devices next
    /// to it are there all the same, and the rule is the user's promise that the agent stays out
    /// of them. A lamp without the rule, or with allow rules above it, keeps what it says.
    ///
    /// @param localAddresses the machine's addresses with the prefix lengths of their networks, as
    ///                       [Machine#localAddresses()] gives them
    NetworkPolicy withLocalNetworks(Tuple<String> localAddresses) {
        Tuple<Cidr> networks = Tuple.of(Cidr.class);
        for (String text : localAddresses) {
            Optional<Cidr> network = localNetwork(text);
            if (network.isPresent() && !networks.contains(network.get())) networks = networks.add(network.get());
        }
        if (networks.isEmpty()) return this;
        Tuple<Rule> extended = Tuple.of(Rule.class);
        for (Rule rule : rules)
            extended = extended.add(rule.label().equals(DEFAULT_DENY_RULE_LABEL) && rule.action() == Decision.DENY
                    ? new Rule(rule.label(), rule.action(), rule.hosts(), rule.ports(), rule.cidrs().addAll(networks))
                    : rule);
        return new NetworkPolicy(defaultDecision, extended, logAllowed, consoleDenied);
    }

    /// The network one address of the machine is on, such as `2001:db8:5:1::/64` for
    /// `2001:db8:5:1::7/64`. A prefix shorter than any real network's, /8 for IPv4 and /48 for
    /// IPv6, would refuse a large part of the internet, so then only the address itself is.
    private static Optional<Cidr> localNetwork(String addressAndPrefix) {
        int slash = addressAndPrefix.lastIndexOf('/');
        if (slash < 0) return Optional.empty();
        Optional<IpAddress> address = IpAddress.parse(addressAndPrefix.substring(0, slash));
        if (address.isEmpty()) return Optional.empty();
        int shortest = address.get().ipv6() ? 48 : 8;
        return Cidr.parse(addressAndPrefix).map(network -> network.prefixLength() >= shortest ? network
                : new Cidr(address.get(), address.get().bitLength()));
    }
}
