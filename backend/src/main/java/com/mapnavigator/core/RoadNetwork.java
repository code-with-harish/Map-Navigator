package com.mapnavigator.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Builds the road network graph.
 *
 * By default it uses an embedded dataset covering central Bengaluru
 * (24 major intersections, ~60 directed road segments with real
 * coordinates and realistic distances / speed limits).
 *
 * If a PostgreSQL datasource is configured (db.enabled=true), the same
 * network is loaded from the database instead (see database/PostgresDBSetup.sql).
 * Plain JDBC is used so this class has no framework dependencies.
 */
public final class RoadNetwork {

    /** id,name,lat,lon */
    static final String NODES_CSV = """
            1,MG Road,12.9757,77.6060
            2,Trinity Circle,12.9730,77.6190
            3,Cubbon Park,12.9763,77.5929
            4,Vidhana Soudha,12.9794,77.5907
            5,Majestic,12.9767,77.5713
            6,KR Market,12.9622,77.5750
            7,Lalbagh,12.9507,77.5848
            8,Jayanagar,12.9308,77.5838
            9,BTM Layout,12.9166,77.6101
            10,Koramangala,12.9352,77.6245
            11,Domlur,12.9610,77.6387
            12,Indiranagar,12.9719,77.6412
            13,Ulsoor,12.9816,77.6285
            14,Shivajinagar,12.9857,77.6057
            15,Cantonment,12.9932,77.5982
            16,Mekhri Circle,13.0068,77.5813
            17,Malleshwaram,13.0031,77.5643
            18,Yeshwanthpur,13.0230,77.5520
            19,Hebbal,13.0358,77.5970
            20,Marathahalli,12.9569,77.7011
            21,HSR Layout,12.9116,77.6446
            22,Silk Board,12.9177,77.6233
            23,Richmond Circle,12.9634,77.5988
            24,Banashankari,12.9250,77.5468
            """;

    /** from,to,road name,distance km,speed limit kmh  (each row = bidirectional road) */
    static final String EDGES_CSV = """
            1,2,MG Road,1.5,50
            1,3,Kasturba Road,1.5,40
            1,13,Kamaraj Road,1.5,40
            1,23,Residency Road,1.6,40
            2,11,Old Airport Road,2.2,60
            2,13,Ulsoor Road,1.2,50
            3,4,Ambedkar Veedhi,0.6,40
            3,23,Nrupathunga Road,1.6,40
            4,5,KG Road,2.2,40
            4,14,Cubbon Road,1.0,40
            5,6,SJP Road,1.7,30
            5,15,Seshadri Road,3.0,40
            5,17,Platform Road,3.5,40
            6,7,RV Road,1.7,30
            6,23,JC Road,2.0,30
            6,24,Mysore Road Link,6.0,40
            7,8,South End Road,2.4,40
            7,23,Richmond Road,1.8,40
            8,9,Outer Ring Road South,3.0,40
            8,24,Kanakapura Cross,4.2,50
            9,10,Sarjapur Link,2.6,40
            9,22,BTM Main Road,1.6,40
            10,11,Inner Ring Road,3.0,50
            10,21,Sarjapur Road,3.0,40
            10,22,Hosur Road,2.2,40
            11,12,CMH Road,1.4,50
            11,20,Old Airport Road East,7.2,60
            12,13,100 Feet Road,1.6,50
            13,14,MM Road,2.3,40
            14,15,Bellary Road South,1.2,40
            15,16,Bellary Road,2.6,50
            16,17,CV Raman Road,2.2,50
            16,19,Bellary Road North,3.5,60
            17,18,Tumkur Road Link,3.0,50
            18,19,Outer Ring Road North,5.6,60
            20,21,Outer Ring Road East,6.8,60
            21,22,HSR 27th Main,2.4,40
            """;

    private RoadNetwork() {}

    public static Graph embedded() {
        Graph g = new Graph();
        for (String line : NODES_CSV.strip().split("\n")) {
            String[] p = line.strip().split(",");
            g.addNode(new Graph.Node(Integer.parseInt(p[0]), p[1],
                    Double.parseDouble(p[2]), Double.parseDouble(p[3])));
        }
        for (String line : EDGES_CSV.strip().split("\n")) {
            String[] p = line.strip().split(",");
            g.addRoad(Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2],
                    Double.parseDouble(p[3]), Double.parseDouble(p[4]));
        }
        return g;
    }

    /** Loads the network from PostgreSQL (schema in database/PostgresDBSetup.sql). */
    public static Graph fromDatabase(String jdbcUrl, String user, String password) throws Exception {
        Graph g = new Graph();
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, password);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id, name, lat, lon FROM nodes")) {
                while (rs.next()) {
                    g.addNode(new Graph.Node(rs.getInt(1), rs.getString(2),
                            rs.getDouble(3), rs.getDouble(4)));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT from_node, to_node, road_name, distance_km, speed_limit_kmh FROM edges")) {
                while (rs.next()) {
                    g.addDirectedEdge(new Graph.Edge(rs.getInt(1), rs.getInt(2),
                            rs.getString(3), rs.getDouble(4), rs.getDouble(5)));
                }
            }
        }
        return g;
    }
}
