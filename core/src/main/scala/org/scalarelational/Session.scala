package org.scalarelational

import java.sql.{Blob, Connection, SQLException, Statement}

import org.scalarelational.datatype.TypedValue
import org.scalarelational.model.Datastore


case class Session(datastore: Datastore, var inTransaction: Boolean = false) {
  private var _disposed = false
  private var _connection: Option[Connection] = None

  def hasConnection: Boolean = _connection.nonEmpty

  def connection: Connection = _connection match {
    case _ if disposed => throw new RuntimeException("Session is disposed.")
    case Some(c) => c
    case None =>
      val c = datastore.dataSource.map(_.getConnection)
      _connection = c
      c.get
  }

  def execute(sql: String) = {
    Datastore.current(datastore)
    val statement = connection.createStatement()
    try {
      statement.execute(sql)
    } catch {
      case t: Throwable => throw new SQLException(s"Failed to execute statement for: $sql", t)
    } finally {
      statement.close()
    }
  }

  def executeUpdate(sql: String, args: List[TypedValue[_, _]]) = {
    Datastore.current(datastore)
    val ps = connection.prepareStatement(sql)
    try {
      args.zipWithIndex.foreach {
        case (arg, index) => ps.setObject(index + 1, arg.value, arg.dataType.jdbcType)
      }
      ps.executeUpdate()
    } catch {
      case t: Throwable => throw new SQLException(s"Failed to execute update for: $sql (args: ${args.mkString(", ")})", t)
    } finally {
      ps.close()
    }
  }

  def executeInsert(sql: String, args: Seq[TypedValue[_, _]]) = {
    Datastore.current(datastore)
    val ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
    args.zipWithIndex.foreach { case (arg, index) =>
      arg.value match {
        case b: Blob => ps.setBinaryStream(index + 1, b.getBinaryStream)
        case _ => ps.setObject(index + 1, arg.value, arg.dataType.jdbcType)
      }
    }
    try {
      ps.executeUpdate()
      ps.getGeneratedKeys
    } catch {
      case t: Throwable => throw new SQLException(s"Failed to execute insert for: $sql (args: ${args.mkString(", ")})", t)
    }
  }

  def executeInsertMultiple(sql: String, rows: Seq[Seq[TypedValue[_, _]]]) = {
    Datastore.current(datastore)
    val ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
    rows.foreach {
      case args => {
        args.zipWithIndex.foreach {
          case (arg, index) => ps.setObject(index + 1, arg.value, arg.dataType.jdbcType)
        }
        ps.addBatch()
      }
    }
    try {
      ps.executeBatch()
      ps.getGeneratedKeys
    } catch {
      case t: Throwable => throw new SQLException(s"Failed to execute update for: $sql (rows: ${rows.mkString(", ")})", t)
    }
  }

  def executeQuery(sql: String, args: Seq[TypedValue[_, _]], fetchSize: Int) = {
    Datastore.current(datastore)
    val ps = connection.prepareStatement(sql)
    ps.setFetchSize(fetchSize)
    args.zipWithIndex.foreach {
      case (typed, index) => ps.setObject(index + 1, typed.value, typed.dataType.jdbcType)
    }
    ps.executeQuery()
  }

  def autoCommit = connection.getAutoCommit
  def autoCommit(b: Boolean) = connection.setAutoCommit(b)
  def transactionMode = TransactionMode.byValue(connection.getTransactionIsolation)
  def transactionMode_=(mode: TransactionMode) = connection.setTransactionIsolation(mode.value)
  def savePoint(name: String) = connection.setSavepoint(name)

  def commit() = connection.commit()
  def rollback() = connection.rollback()

  def disposed = _disposed

  protected[scalarelational] def dispose() = if (!disposed) {
    _connection match {
      case Some(c) => c.close()
      case None => // No connection ever created
    }
    _disposed = true
  }
}