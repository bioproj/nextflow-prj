package bioproj.mongo

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Channel
import nextflow.Global
import nextflow.Session
import org.apache.kafka.common.protocol.types.Field.Str
import org.bson.Document

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.CompletableFuture

@Slf4j
class QueryHandler implements QueryOp<QueryHandler> {
    private Integer batchSize
    private String url
    private DataflowWriteChannel target
    private String databases
    private String collection
    private String id


    @Override
    QueryOp withStatement(String stm) {
        return null
    }

    @Override
    QueryOp withTarget(DataflowWriteChannel channel) {
        this.target = channel
        return this
    }
    @Override
    QueryOp withUrl(String url) {
        this.url = url
        return this
    }

    @Override
    QueryOp withDatabase(String database) {
        this.databases = database
        return this
    }

    @Override
    QueryOp withCollection(String collections) {
        this.collection =collections
        return this
    }

    @Override
    QueryOp withId(String id) {
        this.id=id;
        return this
    }

    @Override
    QueryOp withOpts(Map opts) {
//        if( opts.emitColumns )
//            this.emitColumns = opts.emitColumns as boolean
        if( opts.batchSize )
            this.batchSize = opts.batchSize as Integer
        if( opts.url)
            this.url =opts.url
        if( opts.databases)
            this.databases =opts.databases
        if( opts.collection)
            this.collection =opts.collection
//        if( opts.batchDelay )
//            this.batchDelayMillis = opts.batchDelay as long
        return this
    }



    @Override
    QueryHandler perform(boolean async) {
//        final conn = null //connect(dataSource ?: SqlDataSource.DEFAULT)
        MongoClient mongoClient = MongoClients.create(url);
        println("mongo url:"+url)
//        MongoDatabase database = mongoClient.getDatabase(databases);
//        MongoCollection<Document> collection = database.getCollection(collection);
        if( async )
            queryAsync(mongoClient)
        else
            queryExec(mongoClient)
        return this
    }

    protected queryAsync(MongoClient conn) {
        def future = CompletableFuture.runAsync ({ queryExec(conn) })
        future.exceptionally(this.&handlerException)
    }
    static private void handlerException(Throwable e) {
        final error = e.cause ?: e
        log.error(error.message, error)
        final session = Global.session as Session
        session?.abort(error)
    }

    protected void queryExec(MongoClient conn) {
//        if( batchSize ) {
//            query1(conn)
//        }
//        else {
            query0(conn)
//        }
    }
    protected void query0(MongoClient mongoClient) {
        try{
            MongoDatabase database = mongoClient.getDatabase(databases);
            MongoCollection<Document> collections = database.getCollection(collection);
            Document query = new Document("workflowId",id);
            println("mongo databases:"+databases)
            println("mongo collection:"+collection)
            println("mongo id:"+id)
            def  samples = collections.find(query).findAll();
            if( samples ==null) return
//        System.out.println(first);
//            if(first.samples){
//                def smaples = first.samples
            for(it in samples){
                def mata = [name: it['name'],dataKey: it['dataKey'], species: it['species'],"workflowId":id,"singleEnd":false]
                def fastq = [it['fastq1'], it['fastq2']]
                target.bind([mata,fastq])
            }

//            }
        }finally{
            target.bind(Channel.STOP)
            mongoClient.close();
        }
//        target.bind(item)



//        try {
//            try (Statement stm = conn.createStatement()) {
//                try( def rs = stm.executeQuery(normalize(statement)) ) {
//                    if( emitColumns )
//                        emitColumns(rs)
//                    emitRowsAndClose(rs)
//                }
//            }
//        }
//        finally {
//            conn.close()
//        }
    }
//    protected void query1(MongoDatabase conn) {
//        try {
//            // create the query adding the `offset` and `limit` params
//            final query = makePaginationStm(statement)
//            // create the prepared statement
//            try (PreparedStatement stm = conn.prepareStatement(query)) {
//                int count = 0
//                int len = 0
//                do {
//                    final offset = (count++) * batchSize
//                    final limit = batchSize
//
//                    stm.setInt(1, limit)
//                    stm.setInt(2, offset)
//                    queryCount++
//                    try ( def rs = stm.executeQuery() ) {
//                        if( emitColumns && count==1 )
//                            emitColumns(rs)
//                        len = emitRows(rs)
//                        sleep(batchDelayMillis)
//                    }
//                }
//                while( len==batchSize )
//            }
//            finally {
//                // close the channel
//                target.bind(Channel.STOP)
//            }
//        }
//        finally {
//            conn.close()
//        }
//    }
}
