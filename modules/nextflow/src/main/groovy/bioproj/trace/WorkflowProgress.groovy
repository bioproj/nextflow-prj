package bioproj.trace

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import nextflow.trace.ProgressRecord
import nextflow.trace.WorkflowStats

@EqualsAndHashCode
@CompileStatic
class WorkflowProgress {
    private WorkflowStats stats

    WorkflowProgress(WorkflowStats stats) {
        this.stats = stats
    }

    int getSucceeded() { stats.succeededCount }

    int getFailed() { stats.failedCount }

    int getIgnored() { stats.ignoredCount }

    int getCached() { stats.cachedCount }

    int getPending() { stats.pendingCount }

    int getSubmitted() { stats.submittedCount }

    int getRunning() { stats.runningCount }

    int getRetries() { stats.retriesCount }

    int getAborted() { stats.abortedCount }

    int getLoadCpus() { stats.loadCpus }

    long getLoadMemory() { stats.loadMemory }

    int getPeakRunning() { stats.peakRunning }

    long getPeakCpus() { stats.peakCpus }

    long getPeakMemory() { stats.peakMemory }

    List<ProgressRecord> getProcesses() { stats.getProcesses() }

}
