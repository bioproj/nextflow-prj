package bioproj.mongo

class MongoConfig {

    private String taskId
    private String url
    MongoConfig(Map map){
        def config = map ?: Collections.emptyMap()
        taskId = config.taskId
        url = config.url
    }

}
