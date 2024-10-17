package org.prestoncabe.functions;

import io.quarkus.arc.Arc;
import javax.enterprise.context.ApplicationScoped;
import io.quarkus.arc.Unremovable;
import javax.inject.Inject;
import io.agroal.api.AgroalDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Unremovable
@ApplicationScoped
public class LocationService {

    @Inject
    AgroalDataSource dataSource;

    public Connection getDbConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public static List<String> lookup(String column, Map<String, Object> filters) {
        List<String> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ").append(column).append(" FROM locations WHERE ");

        // construct WHERE clause; assume everything is ANDed together
        boolean first = true;
        for (String key : filters.keySet()) {
            if (!first) {
                sql.append(" AND ");
            }
            sql.append(key).append(" = ?");
            first = false;
        }

        LocationService service = Arc.container().instance(LocationService.class).get();

        Connection connection = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try { 
            connection = service.getDbConnection();
            pstmt = connection.prepareStatement(sql.toString());

            // Set the values dynamically
            int index = 1;
            for (Object value : filters.values()) {
                String stringValue = value.toString(); // db only has strings
                pstmt.setString(index++, stringValue);
            }

            rs = pstmt.executeQuery();

            while(rs.next()) {
                String thisString = rs.getString(column);
                results.add(thisString);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
                if (pstmt != null) {
                    pstmt.close();
                }
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        return results;
            
    }    
}