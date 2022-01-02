/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.ast.factory.neo4j

import org.neo4j.cypher.internal.ast.Clause
import org.neo4j.cypher.internal.ast.Statement
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.parser.javacc.Cypher
import org.neo4j.cypher.internal.parser.javacc.CypherCharStream
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.OpenCypherExceptionFactory

trait JavaccRule[+T] {
  def apply(queryText: String): T
}

object JavaccRule {

  type Parser = cypherJavaccParserFactory.Type

  def fromParser[T](runParser: Parser => T): JavaccRule[T] = fromQueryAndParser(identity, runParser)

  def fromQueryAndParser[T](transformQuery: String => String, runParser: Parser => T): JavaccRule[T] = { queryText: String =>
    val p = cypherJavaccParserFactory(transformQuery(queryText))
    val res = runParser(p)
    p.EndOfFile()
    res
  }

  def Statement: JavaccRule[Statement] = fromParser(_.Statement())
  def Clause: JavaccRule[Clause] = fromParser(_.Clause())
  def Expression: JavaccRule[Expression] = fromParser(_.Expression())
  def Variable: JavaccRule[Variable] = fromParser(_.Variable())

  // ParserFactory is only really needed to create the Parser type alias above without writing down all 30+ type parameters that Cypher[A,B,C,..] has.
  trait ParserFactory[P] {
    type Type = P
    def apply(q: String): P
  }

  object ParserFactory {
    def apply[P](f: String => P): ParserFactory[P] = q => f(q)
  }

  private val exceptionFactory = OpenCypherExceptionFactory(None)

  //noinspection TypeAnnotation
  val cypherJavaccParserFactory = ParserFactory { queryText: String =>
    val charStream = new CypherCharStream(queryText)
    val astFactory = new Neo4jASTFactory(queryText, new AnonymousVariableNameGenerator())
    val astExceptionFactory = new Neo4jASTExceptionFactory(exceptionFactory)
    new Cypher(astFactory, astExceptionFactory, charStream)
  }
}
