<%
    // oillamp's version of spock-reports' Markdown template. It differs in a few places, so that
    // the reports read as documentation: a specification's @Narrative is rendered as Markdown,
    // not inside <pre>, and a scenario's reportInfo as a paragraph, not as an indented list item
    // that Markdown shows as code. Both lose the indentation they have in the source, so that an
    // ASCII drawing in a ``` block keeps its shape. A rolled-up scenario says its reportInfo once,
    // not once for each row. And a cell of a table of rows can never break the table or flood
    // the page: a | is escaped, a line break shows as ⏎, and a long value is cut with ….
    def stats = utils.stats( data )
    def unindented = { String text ->
        def lines = text.readLines()
        def indent = lines.findAll { it.trim() }.collect { it.length() - it.stripLeading().length() }.min() ?: 0
        lines.collect { it.length() >= indent ? it.substring( indent ) : it.trim() }.join( '\n' ).strip()
    }
    def cell = { value ->
        def text = String.valueOf( value ).replace( '\r\n', '\n' ).replace( '\n', '⏎' ).replace( '|', '\\|' )
        text.length() > 80 ? text.substring( 0, 79 ) + '…' : text
    }
 %># Report for ${utils.getSpecClassName( data )}

##Summary

* Total Runs: ${stats.totalRuns}
* Success Rate: ${fmt.toPercentage(stats.successRate)}
* Failures: ${stats.failures}
* Errors:   ${stats.errors}
* Skipped:  ${stats.skipped}
* Total time: ${fmt.toTimeDuration(stats.time)}

<%
    def specTitle = utils.specAnnotation( data, spock.lang.Title )?.value()
    if ( specTitle ) {
        specTitle.split('\n').each { out << '## ' << it << '\n' }
    }
    if ( data.info.narrative ) {
        if ( specTitle ) { out << '\n' }
        out << unindented( data.info.narrative ) << '\n'
    }
    
    def writeTagOrAttachment = { feature ->
        def tagsByKey = feature.tags.groupBy( { t -> t.key } )
        tagsByKey.each { key, values ->
            out << '\n#### ' << key.capitalize() << 's:\n\n'
            values.each { tag ->
                out << '* ' << tag.url << '\n'
            }
        }
        if ( feature.attachments.size() > 0 ) {
            out << '\n#### ' << 'See:' << '\n\n'
            feature.attachments.each { value ->
                out << '* ' << value.url << '\n'
            } 
        }
    }
    def writePendingFeature = { pendingFeature ->
        if ( pendingFeature ) {
            out << '\n> Pending Feature\n'
        }
    }
    def writeHeaders = { headers ->
        if ( headers ) {
            headers.each { h ->
                out << '> ' << h << '\n'
            }
        }
    }
    def writeExtraInfo = { extraInfo ->
        if ( extraInfo ) {
            extraInfo.collect { unindented( it.toString() ) }.unique().each { info ->
                out << '\n' << info << '\n'
            }
        }
    }
    writeHeaders( utils.specHeaders( data ) )
    writeTagOrAttachment data.info
%>

## Features
<%
    features.eachFeature { name, result, blocks, iterations, params ->
%>
### $name
<%
 writePendingFeature( featureMethod.getAnnotation( spock.lang.PendingFeature ) )
 def feature = delegate
 writeTagOrAttachment( feature )
 if (result != "IGNORED") {
      if ( utils.isUnrolled( feature ) ) {
          iterations.each { iter ->
              writeExtraInfo( utils.nextSpecExtraInfo( data, feature, iter.info ) )
          }
      } else {
          writeExtraInfo( utils.nextSpecExtraInfo( data, feature ) )
     }
 }
 def iterationTimes = iterations.collect { it.time ?: 0L }
 def totalTime = fmt.toTimeDuration( iterationTimes.sum() )
%>
Result: **$result**
Time: $totalTime
<%
        for ( block in blocks ) {
 %>
* ${block.kind} ${block.text}
<%
          if ( block.sourceCode && block.kind != 'Where:' ) {
              out << "\n```\n"
              block.sourceCode.each { codeLine ->
                  out << codeLine << '\n'
              }
              out << "```\n"
          }
        }
        def executedIterations = iterations.findAll { it.dataValues || it.errors }
        
        if ( params && executedIterations ) {
            def iterationReportedTimes = executedIterations.collect { it.time ?: 0L }
                        .collect { fmt.toTimeDuration( it ) }
            def maxTimeLength = iterationReportedTimes.collect { it.size() }.sort().last()
 %>

| ${params.collect { cell( it ) }.join( ' | ' )} | time | result |
|${params.collect { '---|' }.join()}---|---|
<%
            executedIterations.eachWithIndex { iteration, i ->
%>| ${iteration.dataValues.collect { cell( it ) }.join( ' | ' )} | ${iterationReportedTimes[ i ]} | ${iteration.errors ? 'FAIL' : 'PASS'} |
<%          }
        }
        def problems = executedIterations.findAll { it.errors }
        if ( problems ) {
            out << "\nThe following problems occurred:\n\n"
            for ( badIteration in problems ) {
                if ( badIteration.dataValues ) {
                    out << '* ' << badIteration.dataValues.collect { cell( it ) }.join( ', ' ) << '\n'
                }
                for ( error in badIteration.errors ) {
                    out << '```\n' << error << '\n```\n'
                }
            }
        }
    }
 %>

<small>Generated by <a href="<%out << com.athaydes.spockframework.report.SpockReportExtension.PROJECT_URL%>">Athaydes Spock Reports</a></small>