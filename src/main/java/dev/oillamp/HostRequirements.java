package dev.oillamp;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/// The host packages oillamp needs on one [DistroFamily], each with the reason it is needed.
///
/// The reasons are shown to the user before anything is installed, so they know what is about
/// to change on their machine and why. The same program has a different package name in some
/// families; [DistroFamily#UNKNOWN] gets the Debian names, which are listed for the user to
/// look up.
record HostRequirements(DistroFamily family, Tuple<String> packages,
                               Association<String, String> reasons) {

    /// One thing oillamp needs, with its package name in each family.
    private record Need(String debian, String fedora, String suse, String arch, String reason) {
        String nameIn(DistroFamily family) {
            return switch (family) {
                case DEBIAN, UNKNOWN -> debian;
                case FEDORA          -> fedora;
                case SUSE            -> suse;
                case ARCH            -> arch;
            };
        }
    }

    private static final Tuple<Need> NEEDS = Tuple.of(Need.class,
        new Need("podman", "podman", "podman", "podman",
                 "runs the sandbox container, rootless and without a daemon"),
        new Need("crun", "crun", "crun", "crun",
                 "the OCI runtime podman uses to start the container; Ubuntu "
               + "defaults to runc, which cannot pass the GPU render group in"),
        new Need("slirp4netns", "slirp4netns", "slirp4netns", "slirp4netns",
                 "gives rootless podman a network while the image is built; "
               + "the sandbox itself then runs with --network=none"),
        new Need("uidmap", "shadow-utils", "shadow", "shadow",
                 "newuidmap/newgidmap, which rootless podman needs to map container users"),
        new Need("catatonit", "catatonit", "catatonit", "catatonit",
                 "the init process inside the container, so orphaned processes get reaped"),
        new Need("socat", "socat", "socat", "socat",
                 "bridges the SSH connection over a Unix socket instead of a TCP port"),
        new Need("openssh-client", "openssh-clients", "openssh-clients", "openssh",
                 "ssh and ssh-keygen for the terminal window and the per-lamp keys"),
        new Need("tigervnc-viewer", "tigervnc", "tigervnc", "tigervnc",
                 "the window in which you watch the agent's desktop"));

    public static HostRequirements forFamily(DistroFamily family) {
        Association<String, String> why = Association.betweenSorted(String.class, String.class);
        for (Need need : NEEDS)
            why = why.put(need.nameIn(family), need.reason());
        return new HostRequirements(family, Tuple.of(String.class, why.keySet()), why);
    }

    /// The packages from this table that are not installed yet, in table order.
    public Tuple<String> missingFrom(ValueSet<String> installed) {
        Tuple<String> missing = Tuple.of(String.class);
        for (String pkg : packages)
            if (!installed.contains(pkg)) missing = missing.add(pkg);
        return missing;
    }
}
