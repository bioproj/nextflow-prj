package bioproj.mongo

import groovyx.gpars.dataflow.DataflowWriteChannel

interface QueryOp<T extends QueryOp> {

    QueryOp withStatement(String stm)
    QueryOp withTarget(DataflowWriteChannel channel)
//    QueryOp withDataSource(SqlDataSource ds)
    QueryOp withOpts(Map options)
    QueryOp withUrl(String url);
    QueryOp withPort(Integer port);
    QueryOp withAddress(String address);
    QueryOp withPassword(String password);
    QueryOp withUsername(String username);
    QueryOp withDatabase(String database);
    QueryOp withCollection(String collections);
    QueryOp withId(String id);


    T perform(boolean async)
}
