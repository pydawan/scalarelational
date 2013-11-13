package com.outr.query.h2

import com.outr.query._
import org.h2.jdbcx.JdbcConnectionPool
import org.powerscala.reflect.EnhancedClass
import com.outr.query.Column
import com.outr.query.Insert
import java.sql.Statement
import java.io.NotSerializableException
import scala.collection.mutable.ListBuffer
import org.powerscala.log.Logging
import com.outr.query.property.{ForeignKey, Unique, AutoIncrement, NotNull}

/**
 * @author Matt Hicks <matt@outr.com>
 */
class H2Datastore protected(mode: H2ConnectionMode = H2Memory(),
                            user: String = "sa",
                            password: String = "sa") extends Datastore with Logging {
  Class.forName("org.h2.Driver")

  val dataSource = JdbcConnectionPool.create(mode.url, user, password)

  def createTableSQL(ifNotExist: Boolean, table: Table) = {
    val b = new StringBuilder

    b.append("CREATE TABLE ")
    if (ifNotExist) {
      b.append("IF NOT EXISTS ")
    }
    b.append(table.tableName)
    b.append('(')
    b.append(table.columns.map(c => column2SQL(c)).mkString(", "))

    if (table.primaryKeys.nonEmpty) {
      b.append(s", PRIMARY KEY(${table.primaryKeys.map(c => c.name).mkString(", ")})")
    }
    table.foreignKeys.foreach {
      case c => {
        val foreignKey = ForeignKey(c).foreignColumn
        b.append(s", FOREIGN KEY(${c.name}) REFERENCES ${foreignKey.table.tableName} (${foreignKey.name})")
      }
    }

    b.append(')')

    b.toString()
  }

  def column2SQL(column: Column[_]) = {
    val b = new StringBuilder
    b.append(column.name)
    b.append(' ')
    b.append(columnType(column.manifest.runtimeClass))
    if (column.has(NotNull)) {
      b.append(" NOT NULL")
    }
    if (column.has(AutoIncrement)) {
      b.append(" AUTO_INCREMENT")
    }
    if (column.has(Unique)) {
      b.append(" UNIQUE")
    }
    b.toString()
  }

  def columnType(c: Class[_]) = EnhancedClass.convertClass(c) match {
    case "Int" => "INTEGER"
    case "Long" => "BIGINT"
    case "Double" => "DOUBLE"
    case "String" => s"VARCHAR(${Int.MaxValue})"
    case classType => throw new RuntimeException(s"Unsupported column-type: $classType ($c).")
  }

  private def expression2SQL(expression: SelectExpression) = expression match {
    case c: ColumnLike[_] => c.longName
    case f: SimpleFunction[_] => s"${f.functionType.name.toUpperCase}(${f.column.longName})"
  }

  def exec(query: Query) = active {
    val columns = query.expressions.map(expression2SQL).mkString(", ")

    var args = List.empty[Any]

    // Generate SQL
    val (joins, joinArgs) = joins2SQL(query.joins)
    args = args ::: joinArgs
    val (where, whereArgs) = where2SQL(query.whereCondition)
    args = args ::: whereArgs
    val groupBy = if (query._groupBy.nonEmpty) {
      s" GROUP BY ${query._groupBy.map(expression2SQL).mkString(", ")}"
    } else {
      ""
    }
    val orderBy = if (query._orderBy.nonEmpty) {
      s" ORDER BY ${query._orderBy.map(ob => s"${expression2SQL(ob.expression)} ${ob.direction.sql}").mkString(", ")}"
    } else {
      ""
    }
    val limit = if (query._limit != -1) {
      s" LIMIT ${query._limit}"
    } else {
      ""
    }
    val offset = if (query._offset != -1) {
      s" OFFSET ${query._offset}"
    } else {
      ""
    }
    val sql = new StringBuilder(s"SELECT $columns FROM ${query.table.tableName}$joins$where$groupBy$orderBy$limit$offset")
//    info(sql)

    val ps = session.connection.prepareStatement(sql.toString())

    // Populate args
    args.zipWithIndex.foreach {
      case (value, index) => ps.setObject(index + 1, value2SQLValue(null, value))
    }

    val resultSet = ps.executeQuery()
    new QueryResultsIterator(resultSet, query)
  }

  def exec(insert: Insert) = active {
    val table = insert.values.head.column.table
    val columnNames = insert.values.map(cv => cv.column.name).mkString(", ")
    val columnValues = insert.values.map(cv => value2SQLValue(cv.column, cv.value))
    val placeholder = columnValues.map(v => "?").mkString(", ")
    val insertString = s"INSERT INTO ${table.tableName} ($columnNames) VALUES($placeholder)"
    val ps = session.connection.prepareStatement(insertString, Statement.RETURN_GENERATED_KEYS)
    columnValues.zipWithIndex.foreach {
      case (value, index) => try {
        ps.setObject(index + 1, value)
      } catch {
        case exc: NotSerializableException => throw new RuntimeException(s"Index: $index (zero-based) is not serializable for insert($columnNames)", exc)
      }
    }
    ps.executeUpdate()
    val keys = ps.getGeneratedKeys
    new GeneratedKeysIterator(keys)
  }

  def exec(update: Update) = active {
    var args = List.empty[Any]
    val sets = update.values.map(cv => s"${cv.column.longName}=?").mkString(", ")
    val setArgs = update.values.map(cv => value2SQLValue(cv.column, cv.value))
    args = args ::: setArgs

    val (where, whereArgs) = where2SQL(update.whereCondition)
    args = args ::: whereArgs
    val sql = s"UPDATE ${update.table.tableName} SET $sets$where"
    val ps = session.connection.prepareStatement(sql)

    // Populate args
    args.zipWithIndex.foreach {
      case (value, index) => ps.setObject(index + 1, value)
    }

    ps.executeUpdate()
  }

  def exec(delete: Delete) = active {
    var args = List.empty[Any]

    val (where, whereArgs) = where2SQL(delete.whereCondition)
    args = args ::: whereArgs
    val sql = s"DELETE FROM ${delete.table.tableName}$where"
    val ps = session.connection.prepareStatement(sql)

    // Populate args
    args.zipWithIndex.foreach {
      case (value, index) => ps.setObject(index + 1, value)
    }

    ps.executeUpdate()
  }

  def condition2String(condition: Condition, args: ListBuffer[Any]): String = condition match {
    case c: ColumnCondition[_] => {
      s"${c.column.longName} ${c.operator.symbol} ${c.other.longName}"
    }
    case c: DirectCondition[_] => {
      args += value2SQLValue(c.column, c.value)
      s"${c.column.longName} ${c.operator.symbol} ?"
    }
    case c: LikeCondition[_] => throw new UnsupportedOperationException("LikeCondition isn't supported yet!")
    case c: RangeCondition[_] => throw new UnsupportedOperationException("RangeCondition isn't supported yet!")
    case c: Conditions => c.list.map(condition => condition2String(condition, args)).mkString(s" ${c.connectType.name.toUpperCase} ")
  }

  private def joins2SQL(joins: List[Join]): (String, List[Any]) = {
    val args = ListBuffer.empty[Any]

    val b = new StringBuilder
    joins.foreach {
      case join => {
        val pre = join.joinType match {
          case JoinType.Inner => " INNER JOIN "
          case JoinType.Join => " JOIN "
          case JoinType.Left => " LEFT JOIN "
          case JoinType.LeftOuter => " LEFT OUTER JOIN "
        }
        b.append(pre)
        b.append(join.table.tableName)
        if (join.alias != null) {
          b.append(s" AS ${join.alias}")
        }
        b.append(" ON ")
        b.append(condition2String(join.condition, args))
      }
    }

    (b.toString(), args.toList)
  }

  private def where2SQL(condition: Condition): (String, List[Any]) = if (condition != null) {
    val args = ListBuffer.empty[Any]
    val sql = condition2String(condition, args)
    if (sql != null && sql.nonEmpty) {
      s" WHERE $sql" -> args.toList
    } else {
      "" -> Nil
    }
  } else {
    "" -> Nil
  }

  /*private def where2SQL(whereBlock: WhereBlock, inner: Boolean = false): (String, List[Any]) = {
    val args = ListBuffer.empty[Any]

    val sql = whereBlock match {
      case null => "" // Nothing to do, no where block
      case where: SingleWhereBlock if !inner => s" WHERE ${condition2String(where.condition, args)}"
      case where: SingleWhereBlock => s"${condition2String(where.condition, args)}"
      case where: MultiWhereBlock => {
        val b = new StringBuilder
        b.append(" WHERE ")
        var first = true
        where.blocks.foreach {
          case block => {
            if (first) {
              first = false
            } else {
              where.connectType match {
                case ConnectType.And => b.append(" AND ")
                case ConnectType.Or => b.append(" OR ")
              }
            }
            val (blockSQL, blockArgs) = where2SQL(block, inner = true)
            args ++= blockArgs
            b.append(blockSQL)
          }
        }
        b.toString()
      }
    }
    sql -> args.toList
  }*/
}