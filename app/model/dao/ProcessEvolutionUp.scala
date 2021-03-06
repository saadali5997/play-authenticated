package model.dao

import java.io.File
import io.getquill.H2JdbcContext
import model.persistence.TableNameSnakeCase

/** Extract the Up portion of Play Evolution file and execute those SQL statements */
object ProcessEvolutionUp {
  lazy val ctx = new H2JdbcContext[TableNameSnakeCase]("quill")
  import ctx._

  /** @param target must be lower case */
  protected def contains(line: String, target: String): Boolean =
    line.toLowerCase.replaceAll("\\s+", " ") contains target

  def apply(file: File): Unit = {
    val upString: String = scala.io.Source.fromFile(file).getLines
      .dropWhile(!contains(_, "# --- !Ups".toLowerCase))
      .drop(1)
      .takeWhile(!contains(_, "# --- !Downs".toLowerCase))
      .mkString("\n")
    try {
      executeAction(upString)
    } catch {
      case e: Exception =>
        val iKnowThisOne = e.getMessage.contains("Table \"auth_token\" already exists")
        if (!iKnowThisOne) throw e
    }
    ()
  }
}
