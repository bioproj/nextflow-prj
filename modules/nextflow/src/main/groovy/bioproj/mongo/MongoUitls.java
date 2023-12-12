package bioproj.mongo;

import com.mongodb.client.*;
import org.bson.Document;

public class MongoUitls {

    public static void getMongo(){
        MongoClient mongoClient = MongoClients.create("mongodb://192.168.10.177:27017");
        MongoDatabase database = mongoClient.getDatabase("test-api");
        MongoCollection<Document> collection = database.getCollection("task");
        Document query = new Document("_id","daac28b9-11b6-4a4d-a033-292d20d51a13");

        Document first = collection.find(query).first();
        System.out.println(first);
        mongoClient.close();


    }
}
