package org.neo4j.graphql;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.schema.GraphQLSchema;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphql.MetaData.RelationshipInfo;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.FormattedLogProvider;
import org.neo4j.logging.Log;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.neo4j.graphdb.Label.label;
import static org.neo4j.helpers.collection.MapUtil.map;

/**
 * @author mh
 * @since 24.10.16
 */
public class MetaDataTest {

    private GraphDatabaseService db;
    private static Log log = FormattedLogProvider.toPrintWriter(new PrintWriter(System.out)).getLog(MetaDataTest.class);
    private GraphQL graphql;

    @Before
    public void setUp() throws Exception {
        db = new TestGraphDatabaseFactory().newImpermanentDatabase();
        ((GraphDatabaseAPI)db).getDependencyResolver().resolveDependency(Procedures.class).registerFunction(GraphQLProcedure.class);
        db.execute("CREATE (berlin:Location {name:'Berlin',longitude:13.4, latitude: 52.5, coord:[13.4,52.5]}) WITH berlin UNWIND range(1,5) as id CREATE (:User {name:'John '+id, id:id, age:id})-[:LIVES_IN]->(berlin)").close();
        GraphQLSchema graphQLSchema = GraphQLSchemaBuilder.buildSchema(db);
        graphql = new GraphQL(graphQLSchema);
    }

    @After
    public void tearDown() throws Exception {
        db.shutdown();
    }

    @Test
    public void sampleRelationships() throws Exception {
        try (Transaction tx = db.beginTx()) {
            MetaData person = GraphSchemaScanner.from(db, label("User"));
            RelationshipInfo livesInLocation = new RelationshipInfo("livesIn","LIVES_IN", "Location", true, false, null,null);
            assertEquals(map("livesIn", livesInLocation), person.relationships);
            MetaData location = GraphSchemaScanner.from(db, label("Location"));
            RelationshipInfo personLivesIn = new RelationshipInfo("livesIn","LIVES_IN", "User", false, true, null,null);
            assertEquals(map("livesIn", personLivesIn), location.relationships);
            tx.success();
        }
    }

    @Test
    public void allUsersQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserQuery { User {id,name,age} User {age,name}}", map());
        assertEquals(2*5, result.get("User").size());
    }

    @Test
    public void firstUserQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("{ User(first:1) {id,name,age} }", map());
        assertEquals(1, result.get("User").size());
    }

    @Test
    public void offsetUserQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("{ User(offset:2) {id,name,age} }", map());
        assertEquals(3, result.get("User").size());
    }

    @Test
    public void firstOffsetUserQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("{ User(first:2,offset:1) {id,name,age} }", map());
        assertEquals(2, result.get("User").size());
    }
    @Test
    public void firstOffsetUseFieldQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("{ Location { name, livesIn(first:2,offset:1) { name } } }", map());
        System.out.println("result = " + result);
        assertEquals(2, ((List)result.get("Location").get(0).get("livesIn")).size());
    }

    @Test
    public void conflictingRelationships() throws Exception {
        db.execute("CREATE (:User {id:-1,name:'Joe'})-[:AGE]->(:Age {age:42})");
        GraphQLSchema graphQLSchema = GraphQLSchemaBuilder.buildSchema(db);
        graphql = new GraphQL(graphQLSchema);
        Map<String, List<Map>> result = executeQuery("query UserQuery { User(name:\"Joe\") {id,name,age,_age { age }}}", map());
        assertEquals(1, result.get("User").size());
    }
    @Test
    public void profileQuery() throws Exception {
        Map<String, Object> result = getBacklog("query UserQuery { User @profile {name} }", map());
        assertEquals(true, result.get("plan").toString().contains("Total database accesses"));
    }

    @Test
    public void explainQuery() throws Exception {
        Map<String, Object> result = getBacklog("query UserQuery { User @explain {name} }", map());
        assertEquals(true, result.get("plan").toString().contains("Estimated Rows"));
    }

    @Test @Ignore
    public void compileQuery() throws Exception {
        Map<String, Object> result = getBacklog("query UserQuery { User @compile {name} }", map());
        assertEquals("READ_ONLY", result.get("type"));
    }

    @Test @Ignore
    public void versionQuery() throws Exception {
        ExecutionResult result = getResult("query UserQuery { User @version(version:\"3.1\") {name} }", map());
        assertEquals(asList(), result.getErrors());
    }

    @Test
    public void allUsersSort() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserSortQuery { User(orderBy:[name_desc,age_desc]) {name,age}}", map());
        List<Map> users = result.get("User");
        int size = users.size();
        assertEquals(5, size);
        for (int i = 0; i < size; i++) {
            assertEquals(5L-i,users.get(i).get("age"));
        }
    }

    @Test
    public void allLocationsQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationQuery { Location {name} }", map());
        assertEquals(1, result.get("Location").size());
        assertEquals("Berlin", result.get("Location").get(0).get("name"));
    }
    @Test
    public void allLocationsQueryNoQueryName() throws Exception {
        Map<String, List<Map>> result = executeQuery("{ Location {name} }", map());
        assertEquals(1, result.get("Location").size());
        assertEquals("Berlin", result.get("Location").get(0).get("name"));
    }

    @Test @Ignore("does not work in library")
    public void skipDirective() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationQuery { Location {name @skip(if: true) } }", map());
        System.out.println("result = " + result);
        assertEquals(1, result.get("Location").size());
        assertEquals(false, result.get("Location").get(0).containsKey("name"));
    }

    @Test
    public void fieldAliasQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationQuery { Location { loc : name} }", map());
        assertEquals(1, result.get("Location").size());
        assertEquals("Berlin", result.get("Location").get(0).get("loc"));
    }

    @Test
    public void geoLocationLatitudeQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationQuery { Location(latitude:52.5) { name} }", map());
        assertEquals(1, result.get("Location").size());
        assertEquals("Berlin", result.get("Location").get(0).get("name"));
    }

    @Test
    public void locationNameQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationQuery { Location(name:\"Berlin\") { name} }", map());
        assertEquals(1, result.get("Location").size());
        assertEquals("Berlin", result.get("Location").get(0).get("name"));
    }
    @Test
    public void locationNameNotMatchQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationQuery { Location(name:\"Bärlin\") { name} }", map());
        assertEquals(0, result.get("Location").size());
    }

    @Ignore("TODO figure out how to denote location input arguments")
    @Test
    public void geoLocationQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationQuery { Location(location : {latitude:52.5,longitude:13.4}) { name} }", map());
        assertEquals(1, result.get("Location").size());
        assertEquals("Berlin", result.get("Location").get(0).get("name"));
    }

    @Test
    public void typeAliasQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationQuery { Loc: Location { name} }", map());
        assertEquals(1, result.get("Loc").size());
        assertEquals("Berlin", result.get("Loc").get(0).get("name"));
    }

    @Test @Ignore("seems not to be supported in the graphql library, or our query generation")
    public void fragmentTest() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationQuery { Location { ...name } }\nfragment name on Location { name } ", map());
        assertEquals(1, result.get("Locaction").size());
        assertEquals("Berlin", result.get("Locaction").get(0).get("name"));
    }

    @Test
    public void singleUserWithLocationQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserWithLocationQuery { User(id:3) {name,livesIn {name} } }", map());
        List<Map> users = result.get("User");
        assertEquals(1, users.size());
        Map user = users.get(0);
        assertEquals("John 3", user.get("name"));
        Map location = (Map) user.get("livesIn");
        assertEquals("Berlin", location.get("name"));
    }

    @Test
    public void singleUserWithLocationUserQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserWithLocationQuery { User(id:3) {name,livesIn {name, livesIn {name} } } }", map());
        List<Map> users = result.get("User");
        assertEquals(1, users.size());
        Map user = users.get(0);
        assertEquals("John 3", user.get("name"));
        Map location = (Map) user.get("livesIn");
        assertEquals("Berlin", location.get("name"));
        List<Map> usersInLocation = (List<Map>) location.get("livesIn");
        assertEquals(5, usersInLocation.size());
    }

    @Test
    public void singleUserWithLocationNameQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserWithLocationQuery { User(id:3) {name,livesIn(name:\"Berlin\") {name} } }", map());
        List<Map> users = result.get("User");
        assertEquals(1, users.size());
        Map user = users.get(0);
        assertEquals("John 3", user.get("name"));
        Map location = (Map) user.get("livesIn");
        assertEquals("Berlin", location.get("name"));
    }

    @Test
    public void singleUserWithLocationNameNoMatchQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserWithLocationQuery { User(id:3) {name,livesIn(name:\"Bärlin\") {name} } }", map());
        List<Map> users = result.get("User");
        assertEquals(1, users.size());
        Map user = users.get(0);
        assertEquals("John 3", user.get("name"));
        Map location = (Map) user.get("livesIn");
        assertEquals(null, location);
    }

    @Test
    public void singleUserWithLocationUserQuery2ndDegree() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserWithLocationWithUserQuery { User(id:3) {name,livesIn { name, livesIn { name } } } }", map());
        List<Map> users = result.get("User");
        assertEquals(1, users.size());
        Map user = users.get(0);
        assertEquals("John 3", user.get("name"));
        Map location = (Map) user.get("livesIn");
        assertEquals("Berlin", location.get("name"));
        List<Map<String,Object>> people = (List<Map<String,Object>>) location.get("livesIn");
        assertEquals(5, people.size());
        people.forEach((p) -> assertEquals(true, p.get("name").toString().startsWith("John")));
    }

    @Test
    public void usersWithLocationQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserWithLocationQuery { User {name,livesIn {name} } }", map());
        List<Map> users = result.get("User");
        assertEquals(5, users.size());
        Map user = users.get(0);
        assertEquals("John 1", user.get("name"));
        Map location = (Map) user.get("livesIn");
        assertEquals("Berlin", location.get("name"));
    }

    @Test
    public void locationWithUsersQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationWithUserQuery { Location {name, livesIn {name,age} } }", map());
        List<Map> locations = result.get("Location");
        assertEquals(1, locations.size());
        Map location = locations.get(0);
        assertEquals("Berlin", location.get("name"));
        List<Map<String,Object>> people = (List<Map<String,Object>>) location.get("livesIn");
        assertEquals(5, people.size());
        people.forEach((p) -> assertEquals(true, p.get("name").toString().startsWith("John")));
    }
    @Test
    public void locationWithUsersSortQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationWithUserQuery { Location {name, livesIn(orderBy:[age_desc]) {name,age} } }", map());
        List<Map> locations = result.get("Location");
        Map location = locations.get(0);
        List<Map<String,Object>> people = (List<Map<String,Object>>) location.get("livesIn");
        int size = people.size();
        assertEquals(5, size);
        for (int i = 0; i < size; i++) {
            assertEquals(5L-i,people.get(i).get("age"));
        }
    }
    @Test
    public void locationWithPeopleQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query LocationWithPersonQuery { Location {name, livesIn {name,age} } }", map());
        List<Map> locations = result.get("Location");
        assertEquals(1, locations.size());
        Map location = locations.get(0);
        assertEquals("Berlin", location.get("name"));
        List<Map> people = (List<Map>) location.get("livesIn");
        assertEquals(5, people.size());
        people.forEach((p) -> assertEquals(true, p.get("name").toString().startsWith("John")));
    }
    @Test
    public void oneUserParameterQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserQuery($name: String!) { User(name:$name) {id,name,age} }", map("name", "John 2"));
        List<Map> users = result.get("User");
        assertEquals(1, users.size());
        assertEquals("John 2", users.get(0).get("name"));
        assertEquals(2L, users.get(0).get("id"));
    }

    @Test
    public void oneUserQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserQuery { User(id:3) {id,name,age} }", map());
        List<Map> users = result.get("User");
        assertEquals(1, users.size());
        assertEquals("John 3", users.get(0).get("name"));
        assertEquals(3L, users.get(0).get("id"));
    }

    @Test
    public void manyUsersQuery() throws Exception {
        Map<String, List<Map>> result = executeQuery("query UserQuery { User(ids:[3,4]) {id,name,age} }", map());
        List<Map> users = result.get("User");
        assertEquals(2, users.size());
        Map john3 = users.get(0);
        assertEquals("John 3", john3.get("name"));
        assertEquals(3L, john3.get("id"));
        Map john4 = users.get(1);
        assertEquals(4L, john4.get("id"));
    }

    private Map<String,List<Map>> executeQuery(String query, Map<String, Object> arguments) {
        return (Map<String,List<Map>>) getResult(query, arguments).getData();
    }

    @NotNull
    private ExecutionResult getResult(String query, Map<String, Object> arguments) {
        GraphQLContext ctx = new GraphQLContext(db,log, map());
        return executeQuery(query, arguments, ctx);
    }

    private Map<String, Object> getBacklog(String query, Map<String, Object> arguments) {
        GraphQLContext ctx = new GraphQLContext(db,log, map());
        executeQuery(query, arguments, ctx);
        return ctx.getBackLog();
    }

    @NotNull
    private ExecutionResult executeQuery(String query, Map<String, Object> arguments, GraphQLContext ctx) {
        System.out.println("query = " + query);
        ExecutionResult result = graphql.execute(query, ctx, arguments);
        Object data = result.getData();
        System.out.println("data = " + data);
        List<GraphQLError> errors = result.getErrors();
        System.out.println("errors = " + errors);
        System.out.println("extensions = " + ctx.getBackLog());
        return result;
    }

}
