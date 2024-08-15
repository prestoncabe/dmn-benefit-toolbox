package org.prestoncabe.functions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Location {
    public static List<String> stateLookup(Map<String, Object> filters) {
        // String workingDir = System.getProperty("user.dir");
        // System.out.println("Working Directory = " + workingDir);

        List<String> results = new ArrayList<>();

        StringBuilder sql = new StringBuilder("SELECT DISTINCT stateAbbreviation FROM locations WHERE ");

        // construct WHERE clause; assume everything is ANDed together
        boolean first = true;
        for (String column : filters.keySet()) {
            if (!first) {
                sql.append(" AND ");
            }
            sql.append(column).append(" = ?");
            first = false;
        }

        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:src/main/resources/data/locations.db");

            PreparedStatement pstmt = connection.prepareStatement(sql.toString());

            // Set the values dynamically
            int index = 1;
            for (Object value : filters.values()) {
                String stringValue = value.toString(); // db only has strings
                pstmt.setString(index++, stringValue);
            }

            ResultSet rs = pstmt.executeQuery();

            while(rs.next()) {
                String thisStateString = rs.getString("stateAbbreviation");
                results.add(thisStateString);
            }

            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return results;
    }

    public static List<String> countyLookup(Map<String, Object> filters) {
        // String workingDir = System.getProperty("user.dir");
        // System.out.println("Working Directory = " + workingDir);
        // System.out.println(filters.toString());

        List<String> results = new ArrayList<>();

        StringBuilder sql = new StringBuilder("SELECT DISTINCT countyFips FROM locations WHERE ");

        // construct WHERE clause; assume everything is ANDed together
        boolean first = true;
        for (String column : filters.keySet()) {
            if (!first) {
                sql.append(" AND ");
            }
            sql.append(column).append(" = ?");
            first = false;
        }

        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:src/main/resources/data/locations.db");

            PreparedStatement pstmt = connection.prepareStatement(sql.toString());

            // Set the values dynamically
            int index = 1;
            for (Object value : filters.values()) {
                String stringValue = value.toString(); // db only has strings
                pstmt.setString(index++, stringValue);
            }

            ResultSet rs = pstmt.executeQuery();

            while(rs.next()) {
                String thisCountyString = rs.getString("countyFips");
                results.add(thisCountyString);
            }

            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return results;
    }

}