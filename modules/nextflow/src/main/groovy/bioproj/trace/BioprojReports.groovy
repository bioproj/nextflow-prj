package bioproj.trace

import bioproj.NextflowFunction
import groovy.json.JsonOutput
import groovy.transform.ToString
import groovy.util.logging.Slf4j
import groovy.yaml.YamlRuntimeException
import groovy.yaml.YamlSlurper
import groovyx.gpars.agent.Agent
import nextflow.Session
import nextflow.file.FileHelper
import nextflow.processor.TaskId
import nextflow.trace.TraceRecord

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Slf4j
class BioprojReports {
    private Session session
    private Path launchReportsPath
    private Path workReportsPath
    private PrintWriter reportsFile
    private boolean processReports
    private Agent<PrintWriter> writer
    private List< Map<String, String>> reportsEntries
    private List<PathMatcher> matchers
    private YamlSlurper yamlSlurper
    private Timer timer
    private AtomicInteger totalReports
    private Thread sender
    private String workflowId

//    void setWorkflowId(String workflowId) {
//        this.workflowId = workflowId
//    }

    @ToString(includeNames = true)
    static class ProcessEvent {
        Map<String, String> report
        boolean completed

    }
    private LinkedBlockingQueue<ProcessEvent> events = new LinkedBlockingQueue()

    BioprojReports(Session session) {
        this.session = session
        this.yamlSlurper = new YamlSlurper()
        this.totalReports = new AtomicInteger(0)

    }

    /**
     * On flow create if there is a tower config yaml for current workflow
     * start writing a reports file at the background.
     *
     * @param workflowId Tower workflow ID
     */
    void flowCreate(String workflowId) {
        this.workflowId = workflowId
        Path launchDir = getLaunchDir()
        reportsEntries = parseReportEntries(launchDir, workflowId)
        processReports = reportsEntries.size() > 0
        if (processReports) {
            final fileName = System.getenv().getOrDefault("TOWER_REPORTS_FILE", "nf-${workflowId}-reports.tsv".toString()) as String
            this.launchReportsPath = launchDir.resolve(fileName)
            this.workReportsPath = session?.workDir?.resolve(fileName)
            this.reportsFile = new PrintWriter(Files.newBufferedWriter(launchReportsPath, Charset.defaultCharset()), true)
            this.writer = new Agent<PrintWriter>(reportsFile)

            // send header
            this.writer.send { PrintWriter it -> it.println("key\tpath\tsize\tdisplay\tmime_type") }

            // Schedule a reports copy if launchDir and workDir are different
            if (this.workReportsPath && this.launchReportsPath != this.workReportsPath) {
                final lastTotalReports = new AtomicInteger(0)
                final task = {
                    // Copy the file only if there are new reports
                    if (lastTotalReports.get() < this.totalReports.get()) {
                        try {
                            final total = this.totalReports.get()
                            log.trace("Reports file sync to workdir with ${total} reports")
                            FileHelper.copyPath(launchReportsPath, workReportsPath, StandardCopyOption.REPLACE_EXISTING)
                            lastTotalReports.set(total)
                        } catch (IOException e) {
                            log.error("Error copying reports file ${launchReportsPath.toUriString()} to the workdir ${workReportsPath.toUriString()} -- ${e.message}")
                        }
                    }
                }

                // Copy maximum 1 time per minute
                final oneMinute = Duration.ofMinutes(1).toMillis()
                this.timer = new Timer()
                this.timer.schedule(task, oneMinute, oneMinute)

            }
        }
    }
    void start(){
        this.sender = Thread.start('Report-thread', this.&sendReports0)
    }

    /**
     * Retrieve current launch dir
     *
     * @return Launch directory path
     */
    protected Path getLaunchDir() {
        return Paths.get('.').toRealPath()
    }

    /**
     * On flow complete stop writing the reports file at background.
     */
    void flowComplete() {
        events << new ProcessEvent(completed: true)
        sender.join()

        if (processReports) {
            if (timer) {
                timer.cancel()
            }
            writer.await()
            // close and upload it
            reportsFile.flush()
            reportsFile.close()
            saveReportsFileUpload()
        }
    }

    protected void saveReportsFileUpload() {
        try {
            FileHelper.copyPath(launchReportsPath, workReportsPath, StandardCopyOption.REPLACE_EXISTING)
            log.debug "Saved reports file ${workReportsPath.toUriString()}"
        }
        catch (Exception e) {
            log.error("Error copying reports file ${launchReportsPath.toUriString()} to the workdir ${workReportsPath.toUriString()} -- ${e.message}")
        }
    }

    /**
     * Load all report entries from tower config yaml file.
     *
     * @param launchDir Nextflow launch directory
     * @param workflowId Tower workflow ID
     */
    protected List<Map.Entry<String, Map<String, String>>> parseReportEntries(Path launchDir, String workflowId) {
        Path towerConfigPath = launchDir.resolve("nf-${workflowId}-tower.yml")

        // Check if Tower config file is define at assets
        if (!Files.exists(towerConfigPath)) {
            towerConfigPath = this.session?.baseDir?.resolve("tower.yml")
        }

        // Load reports definitions if available
        List<Map.Entry<String, Map<String, String>>> reportsEntries = []
        if (towerConfigPath && Files.exists(towerConfigPath)) {
            try {
                final towerConfig = this.yamlSlurper.parse(towerConfigPath)
                if (towerConfig instanceof Map && towerConfig.containsKey("reports")) {
                    Map<String, Map<String, String>> reports = (Map<String, Map<String, String>>) towerConfig.get("reports")
                    for (final e : reports) {
                        reportsEntries.add(e)
                    }
                }
            } catch (YamlRuntimeException e) {
                final msg = e?.cause?.message ?: e.message
                throw new IllegalArgumentException("Invalid tower.yml format -- ${msg}")
            }
        }

        return reportsEntries
    }

    /**
     * On file publish check if the path matches a report pattern a write it to
     * the reports file.
     *
     * @param destination Path of the published file at destination filesystem.
     */
    boolean filePublish(Path destination) {
        if (processReports && destination) {

            if (!matchers) {
                // Initialize report matchers on first event to use the
                // path matcher of the destination filesystem
                matchers = reportsEntries.collect {FileHelper.getPathMatcherFor(convertToGlobPattern(it.key), destination.fileSystem) as PathMatcher}
            }

            for (int p=0; p < matchers.size(); p++) {
                if (matchers.get(p).matches(destination)) {
                    final reportEntry = this.reportsEntries.get(p)
                    def value = new HashMap<>(reportEntry.value)
                    value.put("destination",destination.toString())

                    events << new ProcessEvent(report: value)
                    writer.send((PrintWriter it) -> writeRecord(it, reportEntry, destination))
                    return true
                }
            }
        }
        return false
    }

    protected void sendReports0() {
        List<Map<String, String>> reportsEntries =  new ArrayList<>(100)

//        final tasks = new HashMap<TaskId, TraceRecord>(100)
        boolean complete = false
        long previous = System.currentTimeMillis()
        final long period =  nextflow.util.Duration.of('1 sec').millis
        final long delay = period / 10 as long

        while( !complete ) {
            final ProcessEvent ev = events.poll(delay, TimeUnit.MILLISECONDS)
            // reconcile task events ie. send out only the last event
            if( ev ) {
                log.trace "report event=$ev"
                if( ev.report )
                    reportsEntries.add(ev.report)
//                    tasks[ev.trace.taskId] = ev.trace
                if( ev.completed )
                    complete = true
            }

            // check if there's something to send
            final now = System.currentTimeMillis()
            final delta = now -previous

            if( !reportsEntries ) {
                if( delta > nextflow.util.Duration.of('1 min').millis ) {
//                    final req = makeHeartbeatReq()
//                    final resp = sendHttpMessage(urlTraceHeartbeat, req, 'PUT')
//                    logHttpResponse(urlTraceHeartbeat, resp)

//                    NextflowFunction.writeMessage("nextflow-trace",json,"T-"+workflowId);
                    previous = now
                }
                continue
            }

            if( delta > period || reportsEntries.size() >= 100 || complete ) {
                // send
//                final req = makeTasksReq(tasks.values())
//                final resp = sendHttpMessage(urlTraceProgress, req, 'PUT')
//                logHttpResponse(urlTraceProgress, resp)
                def json = JsonOutput.toJson(reportsEntries)
                println(json)
                NextflowFunction.writeMessage("nextflow-trace",json,"R-"+workflowId);
//                println "1111111111111111111111111111"
                // clean up for next iteration
                previous = now
                reportsEntries.clear()
            }
        }
    }



    private writeRecord(PrintWriter it, Map.Entry<String,Map<String,String>> reportEntry, Path destination) {
        try {
            final target = destination.toUriString()
            final numRep = totalReports.incrementAndGet()
            log.trace("Adding report [${numRep}] ${reportEntry.key} -- ${target}")
            // Report properties
            final display = reportEntry.value.get("display", "")
            final mimeType = reportEntry.value.get("mimeType", "")
            it.println("${reportEntry.key}\t${target}\t${destination.size()}\t${display}\t${mimeType}");
            it.flush()
        }
        catch (Throwable e) {
            log.error ("Unexpected error writing Tower report entry '${destination.toUriString()}'", e)
        }
    }

    protected static String convertToGlobPattern(String reportKey) {
        final prefix = reportKey.startsWith("**/") ? "" : "**/**"
        return "glob:${prefix}${reportKey}"
    }
}
