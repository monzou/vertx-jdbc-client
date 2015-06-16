/*
 * Copyright (c) 2011-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.jdbc;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.impl.actions.AbstractJDBCAction;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.test.core.VertxTestBase;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:nscavell@redhat.com">Nick Scavelli</a>
 */
public abstract class JDBCClientTestBase extends VertxTestBase {

  protected JDBCClient client;

  private static final List<String> SQL = new ArrayList<>();

  static {
    //TODO: Create table with more types for testing
    SQL.add("drop table if exists select_table;");
    SQL.add("drop table if exists insert_table;");
    SQL.add("drop table if exists update_table;");
    SQL.add("drop table if exists delete_table;");
    SQL.add("create table select_table (id int, lname varchar(255), fname varchar(255) );");
    SQL.add("insert into select_table values (1, 'doe', 'john');");
    SQL.add("insert into select_table values (2, 'doe', 'jane');");
    SQL.add("create table insert_table (id int generated by default as identity (start with 1 increment by 1) not null, lname varchar(255), fname varchar(255), dob date );");
    SQL.add("create table update_table (id int, lname varchar(255), fname varchar(255), dob date );");
    SQL.add("insert into update_table values (1, 'doe', 'john', '2001-01-01');");
    SQL.add("create table delete_table (id int, lname varchar(255), fname varchar(255), dob date );");
    SQL.add("insert into delete_table values (1, 'doe', 'john', '2001-01-01');");
    SQL.add("insert into delete_table values (2, 'doe', 'jane', '2002-02-02');");
  }

  @BeforeClass
  public static void createDb() throws Exception {
    Connection conn = DriverManager.getConnection(config().getString("url"));
    for (String sql : SQL) {
      conn.createStatement().execute(sql);
    }
  }

  protected static JsonObject config() {
    return new JsonObject()
      .put("url", "jdbc:hsqldb:mem:test?shutdown=true")
      .put("driver_class", "org.hsqldb.jdbcDriver");
  }

  @Test
  public void testSelect() {
    String sql = "SELECT ID, FNAME, LNAME FROM select_table ORDER BY ID";
    connection().query(sql, onSuccess(resultSet -> {
      assertNotNull(resultSet);
      assertEquals(2, resultSet.getResults().size());
      assertEquals("ID", resultSet.getColumnNames().get(0));
      assertEquals("FNAME", resultSet.getColumnNames().get(1));
      assertEquals("LNAME", resultSet.getColumnNames().get(2));
      JsonArray result0 = resultSet.getResults().get(0);
      assertEquals(1, (int)result0.getInteger(0));
      assertEquals("john", result0.getString(1));
      assertEquals("doe", result0.getString(2));
      JsonArray result1 = resultSet.getResults().get(1);
      assertEquals(2, (int)result1.getInteger(0));
      assertEquals("jane", result1.getString(1));
      assertEquals("doe", result1.getString(2));
      testComplete();
    }));

    await();
  }

  @Test
  public void testSelectWithParameters() {
    String sql = "SELECT ID, FNAME, LNAME FROM select_table WHERE fname = ?";

    connection().queryWithParams(sql, new JsonArray().add("john"), onSuccess(resultSet -> {
      assertNotNull(resultSet);
      assertEquals(1, resultSet.getResults().size());
      assertEquals("ID", resultSet.getColumnNames().get(0));
      assertEquals("FNAME", resultSet.getColumnNames().get(1));
      assertEquals("LNAME", resultSet.getColumnNames().get(2));
      JsonArray result0 = resultSet.getResults().get(0);
      assertEquals(1, (int) result0.getInteger(0));
      assertEquals("john", result0.getString(1));
      assertEquals("doe", result0.getString(2));
      testComplete();
    }));

    await();
  }

  @Test
  public void testSelectWithLabels() {
    String sql = "SELECT ID as \"IdLabel\", FNAME as \"first_name\", LNAME as \"LAST.NAME\" FROM select_table WHERE fname = ?";

    connection().queryWithParams(sql, new JsonArray().add("john"), onSuccess(resultSet -> {
      assertNotNull(resultSet);
      assertEquals(1, resultSet.getResults().size());
      assertEquals("IdLabel", resultSet.getColumnNames().get(0));
      assertEquals("first_name", resultSet.getColumnNames().get(1));
      assertEquals("LAST.NAME", resultSet.getColumnNames().get(2));
      JsonArray result0 = resultSet.getResults().get(0);
      assertEquals(1, (int) result0.getInteger(0));
      assertEquals("john", result0.getString(1));
      assertEquals("doe", result0.getString(2));
      JsonObject row0 = resultSet.getRows().get(0);
      assertEquals(1, (int) row0.getInteger("IdLabel"));
      assertEquals("john", row0.getString("first_name"));
      assertEquals("doe", row0.getString("LAST.NAME"));
      testComplete();
    }));

    await();
  }

  @Test
  public void testSelectTx() {
    String sql = "INSERT INTO insert_table VALUES (?, ?, ?, ?);";
    JsonArray params = new JsonArray().addNull().add("smith").add("john").add("2003-03-03");
    client.getConnection(onSuccess(conn -> {
      assertNotNull(conn);
      conn.setAutoCommit(false, onSuccess(v -> {

        conn.updateWithParams(sql, params, onSuccess((UpdateResult updateResult) -> {
          assertUpdate(updateResult, 1);
          int id = updateResult.getKeys().getInteger(0);
          // Explicit typing of resultset is not really necessary but without it IntelliJ reports
          // syntax error :(
          conn.queryWithParams("SELECT LNAME FROM insert_table WHERE id = ?", new JsonArray().add(id), onSuccess((ResultSet resultSet) -> {
            assertFalse(resultSet.getResults().isEmpty());
            assertEquals("smith", resultSet.getResults().get(0).getString(0));
            testComplete();
          }));
        }));
      }));
    }));

    await();
  }

  @Test
  public void testInvalidSelect() {
    // Suppress log output so this test doesn't look to fail
    setLogLevel(AbstractJDBCAction.class.getName(), Level.SEVERE);
    String sql = "SELECT FROM WHERE FOO BAR";
    connection().query(sql, onFailure(t -> {
      assertNotNull(t);
      testComplete();
    }));

    await();
  }

  @Test
  public void testInsert() {
    String sql = "INSERT INTO insert_table VALUES (null, 'doe', 'john', '2001-01-01');";
    connection().update(sql, onSuccess(result -> {
      assertUpdate(result, 1);
      testComplete();
    }));

    await();
  }

  @Test
  public void testInsertWithParameters() {
    SQLConnection conn = connection();
    String sql = "INSERT INTO insert_table VALUES (?, ?, ?, ?);";
    JsonArray params = new JsonArray().addNull().add("doe").add("jane").add("2002-02-02");
    conn.updateWithParams(sql, params, onSuccess(result -> {
      assertUpdate(result, 1);
      int id = result.getKeys().getInteger(0);
      conn.queryWithParams("SElECT DOB FROM insert_table WHERE id=?;", new JsonArray().add(id), onSuccess(resultSet -> {
        assertNotNull(resultSet);
        assertEquals(1, resultSet.getResults().size());
        assertEquals("2002-02-01T23:00:00Z", resultSet.getResults().get(0).getString(0));
        testComplete();
      }));
    }));

    await();
  }

  @Test
  public void testUpdate() {
    SQLConnection conn = connection();
    String sql = "UPDATE update_table SET fname='jane' WHERE id = 1";
    conn.update(sql, onSuccess(updated -> {
      assertUpdate(updated, 1);
      conn.query("SELECT fname FROM update_table WHERE id = 1", onSuccess(resultSet -> {
        assertNotNull(resultSet);
        assertEquals(1, resultSet.getResults().size());
        assertEquals("jane", resultSet.getResults().get(0).getString(0));
        testComplete();
      }));
    }));

    await();
  }

  @Test
  public void testUpdateWithParams() {
    SQLConnection conn = connection();
    String sql = "UPDATE update_table SET fname = ? WHERE id = ?";
    JsonArray params = new JsonArray().add("bob").add(1);
    conn.updateWithParams(sql, params, onSuccess(result -> {
      assertUpdate(result, 1);
      conn.query("SELECT fname FROM update_table WHERE id = 1", onSuccess(resultSet -> {
        assertNotNull(resultSet);
        assertEquals(1, resultSet.getResults().size());
        assertEquals("bob", resultSet.getResults().get(0).getString(0));
        testComplete();
      }));
    }));

    await();
  }

  @Test
  public void testUpdateNoMatch() {
    SQLConnection conn = connection();
    String sql = "UPDATE update_table SET fname='jane' WHERE id = -231";
    conn.update(sql, onSuccess(result -> {
      assertUpdate(result, 0);
      testComplete();
    }));

    await();
  }

  @Test
  public void testDelete() {
    String sql = "DELETE FROM delete_table WHERE id = 1;";
    connection().update(sql, onSuccess(result -> {
      assertNotNull(result);
      assertEquals(1, result.getUpdated());
      testComplete();
    }));

    await();
  }

  @Test
  public void testDeleteWithParams() {
    String sql = "DELETE FROM delete_table WHERE id = ?;";
    JsonArray params = new JsonArray().add(2);
    connection().updateWithParams(sql, params, onSuccess(result -> {
      assertNotNull(result);
      assertEquals(1, result.getUpdated());
      testComplete();
    }));

    await();
  }

  @Test
  public void testClose() throws Exception {
    client.getConnection(onSuccess(conn -> {
      conn.query("SELECT 1 FROM select_table", onSuccess(results -> {
        assertNotNull(results);
        conn.close(onSuccess(v -> {
          testComplete();
        }));
      }));
    }));

    await();
  }

  @Test
  public void testCloseThenQuery() throws Exception {
    client.getConnection(onSuccess(conn -> {
      conn.close(onSuccess(v -> {
        conn.query("SELECT 1 FROM select_table", onFailure(t -> {
          assertNotNull(t);
          testComplete();
        }));
      }));
    }));

    await();
  }

  @Test
  public void testCommit() throws Exception {
    testTx(3, true);
  }

  @Test
  public void testRollback() throws Exception {
    testTx(5, false);
  }

  private void testTx(int inserts, boolean commit) throws Exception {
    String sql = "INSERT INTO insert_table VALUES (?, ?, ?, ?);";
    JsonArray params = new JsonArray().addNull().add("smith").add("john").add("2003-03-03");
    List<Integer> insertIds = new CopyOnWriteArrayList<>();

    CountDownLatch latch = new CountDownLatch(inserts);
    AtomicReference<SQLConnection> connRef = new AtomicReference<>();
    client.getConnection(onSuccess(conn -> {
      assertNotNull(conn);
      connRef.set(conn);
      conn.setAutoCommit(false, onSuccess(v -> {
        for (int i = 0; i < inserts; i++) {
          // Explicit typing of UpdateResult is not really necessary but without it IntelliJ reports
          // syntax error :(
          conn.updateWithParams(sql, params, onSuccess((UpdateResult result) -> {
            assertUpdate(result, 1);
            int id = result.getKeys().getInteger(0);
            insertIds.add(id);
            latch.countDown();
          }));
        }
      }));
    }));

    awaitLatch(latch);

    StringBuilder selectSql = new StringBuilder("SELECT * FROM insert_table WHERE");
    JsonArray selectParams = new JsonArray();
    for (int i = 0; i < insertIds.size(); i++) {
      selectParams.add(insertIds.get(i));
      if (i == 0) {
        selectSql.append(" id = ?");
      } else {
        selectSql.append(" OR id = ?");
      }
    }

    SQLConnection conn = connRef.get();
    if (commit) {
      conn.commit(onSuccess(v -> {
        client.getConnection(onSuccess(newconn -> {
          // Explicit typing of resultset is not really necessary but without it IntelliJ reports
          // syntax error :(
          newconn.queryWithParams(selectSql.toString(), selectParams, onSuccess((ResultSet resultSet) -> {
            assertEquals(inserts, resultSet.getResults().size());
            testComplete();
          }));
        }));
      }));
    } else {
      conn.rollback(onSuccess(v -> {
        client.getConnection(onSuccess(newconn -> {
          // Explicit typing of resultset is not really necessary but without it IntelliJ reports
          // syntax error :(
          newconn.queryWithParams(selectSql.toString(), selectParams, onSuccess((ResultSet resultSet) -> {
            assertTrue(resultSet.getResults().isEmpty());
            testComplete();
          }));
        }));
      }));
    }

    await();
  }

  private void assertUpdate(UpdateResult result, int updated) {
    assertUpdate(result, updated, false);
  }

  private void assertUpdate(UpdateResult result, int updated, boolean generatedKeys) {
    assertNotNull(result);
    assertEquals(updated, result.getUpdated());
    if (generatedKeys) {
      JsonArray keys = result.getKeys();
      assertNotNull(keys);
      assertEquals(updated, keys.size());
      Set<Integer> numbers = new HashSet<>();
      for (int i = 0; i < updated; i++) {
        assertTrue(keys.getValue(i) instanceof Integer);
        assertTrue(numbers.add(i));
      }
    }
  }

  private SQLConnection connection() {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<SQLConnection> ref = new AtomicReference<>();
    client.getConnection(onSuccess(conn -> {
      ref.set(conn);
      latch.countDown();
    }));

    try {
      latch.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    return ref.get();
  }

  private static void setLogLevel(String name, Level level) {
    Logger logger = Logger.getLogger(name);
    if (logger != null) {
      logger.setLevel(level);
    }
  }
}
