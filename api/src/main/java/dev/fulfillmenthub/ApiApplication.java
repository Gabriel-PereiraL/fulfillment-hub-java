package dev.fulfillmenthub;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiApplication {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("migrate")) {
            Flyway.configure().dataSource(required("DATABASE_URL"), required("DATABASE_USER"), required("DATABASE_PASSWORD"))
                    .locations("classpath:db/migration").load().migrate();
            return;
        }
        if (args.length == 1 && args[0].equals("seed")) {
            seed();
            return;
        }
        SpringApplication.run(ApiApplication.class, args);
    }
    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing configuration: " + name);
        return value;
    }
    private static void seed() {
        var admin=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var customerUser=UUID.fromString("00000000-0000-0000-0000-000000000002");
        var customer=UUID.fromString("00000000-0000-0000-0000-000000000003");
        var encoder=Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();var now=Instant.now();
        try(var connection=DriverManager.getConnection(required("DATABASE_URL"),required("DATABASE_USER"),required("DATABASE_PASSWORD"))){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("""
                    insert into users(id,email,password_hash,active,security_version,roles,created_at,customer_id,version)
                    values (?,?,?,?,?,?,?,?,0) on conflict(id) do nothing""")){
                insertUser(connection,statement,admin,"admin@fulfillment.local",encoder.encode("LocalAdminPassword!"),null,new String[]{"Admin"},now);
                insertUser(connection,statement,customerUser,"customer@fulfillment.local",encoder.encode("LocalCustomerPassword!"),customer,new String[]{"Customer"},now);
            }
            try(var statement=connection.prepareStatement("""
                    insert into customers(id,version,user_id,name,email,phone,active,created_at,updated_at)
                    values (?,0,?,?,?,?,true,?,?) on conflict(id) do nothing""")){
                statement.setObject(1,customer);statement.setObject(2,customerUser);statement.setString(3,"Local Customer");
                statement.setString(4,"customer@fulfillment.local");statement.setString(5,"+5511999999999");statement.setTimestamp(6,java.sql.Timestamp.from(now));statement.setTimestamp(7,java.sql.Timestamp.from(now));statement.executeUpdate();
            }
            try(var statement=connection.prepareStatement("""
                    insert into products(id,sku,name,unit_price_amount,unit_price_currency,stock_quantity,active,created_at,updated_at,version)
                    values (?,?,?,?,'BRL',?,true,?,?,0) on conflict(sku) do nothing""")){
                seedProduct(statement,"10000000-0000-0000-0000-000000000001","FH-COFFEE","Coffee","19.90",100,now);
                seedProduct(statement,"10000000-0000-0000-0000-000000000002","FH-MUG","Mug","29.90",50,now);
            }
            connection.commit();
        }catch(java.sql.SQLException failure){throw new IllegalStateException("Could not seed local database",failure);}
    }
    private static void insertUser(java.sql.Connection connection,java.sql.PreparedStatement statement,UUID id,String email,String hash,UUID customer,String[] roles,Instant now)throws java.sql.SQLException{
        statement.setObject(1,id);statement.setString(2,email);statement.setString(3,hash);statement.setBoolean(4,true);statement.setObject(5,UUID.randomUUID());
        statement.setArray(6,connection.createArrayOf("text",roles));statement.setTimestamp(7,java.sql.Timestamp.from(now));statement.setObject(8,customer);statement.executeUpdate();}
    private static void seedProduct(java.sql.PreparedStatement statement,String id,String sku,String name,String amount,int stock,Instant now)throws java.sql.SQLException{
        statement.setObject(1,UUID.fromString(id));statement.setString(2,sku);statement.setString(3,name);statement.setBigDecimal(4,new java.math.BigDecimal(amount));
        statement.setInt(5,stock);statement.setTimestamp(6,java.sql.Timestamp.from(now));statement.setTimestamp(7,java.sql.Timestamp.from(now));statement.executeUpdate();}
}
