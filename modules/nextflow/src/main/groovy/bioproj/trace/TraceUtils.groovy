package bioproj.trace

import nextflow.Session

import java.time.Instant

class TraceUtils {
    public static Map<String,String> env = System.getenv()
    static Map makeCreateReq(Session session) {
        def result = new HashMap(5)
        result.sessionId = session.uniqueId.toString()
        result.runName = session.runName
        result.projectName = session.workflowMetadata.projectName
        result.repository = session.workflowMetadata.repository
        result.workflowId = env.get('TOWER_WORKFLOW_ID')
        result.instant = Instant.now().toEpochMilli()
        this.towerLaunch = result.workflowId != null
        return result
    }
}
