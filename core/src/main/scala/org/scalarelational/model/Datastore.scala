package org.scalarelational.model

import java.sql.ResultSet
import javax.sql.DataSource

import org.powerscala.event.processor.{EventProcessor, OptionProcessor}
import org.powerscala.event.{EventState, Listenable}
import org.powerscala.log.Logging
import org.scalarelational.column.ColumnLike
import org.scalarelational.column.property.ColumnProperty
import org.scalarelational.datatype.{DataType, TypedValue}
import org.scalarelational.dsl.{DDLDSLSupport, DSLSupport}
import org.scalarelational.fun.BasicFunctionTypes
import org.scalarelational.instruction._
import org.scalarelational.instruction.ddl.DDLSupport
import org.scalarelational.result.ResultSetIterator
import org.scalarelational.table.Table
import org.scalarelational.{Session, PropertyContainer, SessionSupport}


trait Datastore
  extends Listenable
  with Logging
  with SessionSupport
  with DSLSupport
  with SQLContainer
  with DDLSupport
  with DDLDSLSupport
  with BasicFunctionTypes {
  implicit def thisDatastore: Datastore = this

  def DefaultVarCharLength = 65535
  def DefaultBinaryLength  = 1000

  /**
   * All columns that are created receive a DataType responsible for converting data between Scala and the database.
   * This processor receives all of those DataTypes before they are assigned to the column allowing modification by
   * database implementations or other customizations of how the datastore interacts with the database.
   */
  val dataTypeInstanceProcessor = new DataTypeInstanceProcessor
  val value2SQL = new OptionProcessor[(ColumnLike[_, _], Any), Any]("value2SQL")
  val sql2Value = new OptionProcessor[(ColumnLike[_, _], Any), Any]("sql2Value")

  /**
   * True if this database implementation supports merges.
   */
  def supportsMerge = true

  /**
   * True if this database implementation supports multiple id responses on batch insert.
   */
  def supportsBatchInsertResponse = true

  private var _tables = Map.empty[String, Table]
  protected[scalarelational] def add(table: Table) = synchronized {
    _tables += table.tableName.toLowerCase -> table
  }
  def tableByName(name: String) = _tables.get(name.toLowerCase)

  /**
   * Called when the datastore is being created for the first time. This does not mean the tables are being created but
   * just the datastore.
   */
  def creating(): Unit = {}

  def dataSource: DataSource

  def jdbcTables(implicit session: Session) = {
    val s = session
    val meta = s.connection.getMetaData
    val results = meta.getTables(null, "PUBLIC", "%", null)
    try {
      new ResultSetIterator(results).map(_.getString("TABLE_NAME")).toSet
    } finally {
      results.close()
    }
  }

  def jdbcColumns(tableName: String)(implicit session: Session) = {
    val s = session
    val meta = s.connection.getMetaData
    val results = meta.getColumns(null, "PUBLIC", tableName, null)
    try {
      new ResultSetIterator(results).map(_.getString("COLUMN_NAME")).toSet
    } finally {
      results.close()
    }
  }

  def tableExists(name: String)(implicit session: Session) = {
    val meta = session.connection.getMetaData
    val results = meta.getTables(null, "PUBLIC", name.toUpperCase, null)
    try {
      results.next()
    } finally {
      results.close()
    }
  }

  def empty()(implicit session: Session) = jdbcTables.isEmpty

  def create(tables: Table*)(implicit session: Session) = {
    if (tables.isEmpty) throw new RuntimeException(s"Datastore.create must include all tables that need to be created.")
    val sql = ddl(tables.toList)
    sql.result
  }

  /**
   * Converts the `Query` to a SQL `String` and a `List` of arguments.
   *
   * @param query to describe
   * @tparam E expressions
   * @tparam R result
   * @return (String, List[TypedValue[_, _])
   */
  def describe[E, R](query: Query[E, R]): (String, List[TypedValue[_, _]])

  private[scalarelational] final def exec[E, R](query: Query[E, R])(implicit session: Session): ResultSet = {
    val table = query.table
    val q = SQLContainer.beforeInvoke(table, query)
    try {
      invoke[E, R](q)
    } finally {
      SQLContainer.afterInvoke(table, q)
    }
  }
  private[scalarelational] final def exec[T](insert: InsertSingle[T])
                                            (implicit session: Session): Int = {
    val table = insert.table
    val i = SQLContainer.beforeInvoke(table, insert)
    try {
      invoke(i)
    } finally {
      SQLContainer.afterInvoke(table, i)
    }
  }
  private[scalarelational] final def exec(insert: InsertMultiple)
                                         (implicit session: Session): List[Int] = {
    val table = insert.table
    val i = SQLContainer.beforeInvoke(table, insert)
    try {
      invoke(i)
    } finally {
      SQLContainer.afterInvoke(table, i)
    }
  }
  private[scalarelational] final def exec(merge: Merge)(implicit session: Session): Int = {
    val table = merge.table
    val m = SQLContainer.beforeInvoke(table, merge)
    try {
      invoke(m)
    } finally {
      SQLContainer.afterInvoke(table, m)
    }
  }
  private[scalarelational] final def exec[T](update: Update[T])(implicit session: Session): Int = {
    val table = update.table
    val u = SQLContainer.beforeInvoke(table, update)
    try {
      invoke(u)
    } finally {
      SQLContainer.afterInvoke(table, u)
    }
  }
  private[scalarelational] final def exec(delete: Delete)(implicit session: Session): Int = {
    val table = delete.table
    val d = SQLContainer.beforeInvoke(table, delete)
    try {
      invoke(d)
    } finally {
      SQLContainer.afterInvoke(table, d)
    }
  }

  protected def invoke[E, R](query: Query[E, R])(implicit session: Session): ResultSet
  protected def invoke[T](insert: InsertSingle[T])(implicit session: Session): Int
  protected def invoke(insert: InsertMultiple)(implicit session: Session): List[Int]
  protected def invoke(merge: Merge)(implicit session: Session): Int
  protected def invoke[T](update: Update[T])(implicit session: Session): Int
  protected def invoke(delete: Delete)(implicit session: Session): Int

  def dispose() = {}

  implicit class CallableInstructions(instructions: List[CallableInstruction]) {
    def result(implicit session: Session) = {
      instructions.foreach(i => i.execute(thisDatastore))
      instructions.size
    }
    def async = thisDatastore.async { implicit session =>
      result
    }
    def and(moreInstructions: List[CallableInstruction]) = new CallableInstructions(instructions ::: moreInstructions)
  }
}

case class DataTypeInstance[T, S](dataType: DataType[T, S],
                                  columnProperties: Seq[ColumnProperty],
                                  manifest: Manifest[T]) extends PropertyContainer[ColumnProperty] {
  props(columnProperties: _*)
}

class DataTypeInstanceProcessor(implicit val listenable: Listenable) extends EventProcessor[DataTypeInstance[Any, Any], DataType[Any, Any], DataType[Any, Any]] {
  override def name = "dataTypeInstanceProcessor"

  override def eventManifest: Manifest[DataTypeInstance[Any, Any]] = implicitly[Manifest[DataTypeInstance[Any, Any]]]

  override protected def handleListenerResponse(value: DataType[Any, Any], state: EventState[DataTypeInstance[Any, Any]]): Unit = {
    state.event = state.event.copy(dataType = value)
  }

  override protected def responseFor(state: EventState[DataTypeInstance[Any, Any]]): DataType[Any, Any] = state.event.dataType
}

object Datastore {
  private val instance = new ThreadLocal[Datastore]

  protected[scalarelational] def current(d: Datastore) = instance.set(d)
  def apply() = instance.get()
}