/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.s2graph.graphql.repository

import org.apache.s2graph.core.Management.JsonModel._
import org.apache.s2graph.core._
import org.apache.s2graph.core.mysqls._
import org.apache.s2graph.core.rest.RequestParser
import org.apache.s2graph.core.storage.MutateResponse
import org.apache.s2graph.core.types._
import org.apache.s2graph.graphql.types.S2Type._
import sangria.schema._

import scala.concurrent._
import scala.util.{Failure, Try}

object GraphRepository {
}

/**
  *
  * @param graph
  */
class GraphRepository(val graph: S2GraphLike) {

  val management = graph.management
  val parser = new RequestParser(graph)

  implicit val ec = graph.ec

  def toS2EdgeLike(labelName: String, param: AddEdgeParam): S2EdgeLike = {
    graph.toEdge(
      srcId = param.from,
      tgtId = param.to,
      labelName = labelName,
      props = param.props,
      direction = param.direction
    )
  }

  def toS2VertexLike(vid: Any, column: ServiceColumn): S2VertexLike = {
    graph.toVertex(column.service.serviceName, column.columnName, vid)
  }

  def toS2VertexLike(serviceName: String, param: AddVertexParam): S2VertexLike = {
    graph.toVertex(
      serviceName = serviceName,
      columnName = param.columnName,
      id = param.id,
      props = param.props,
      ts = param.timestamp)
  }

  def addVertices(vertices: Seq[S2VertexLike]): Future[Seq[MutateResponse]] = {
    graph.mutateVertices(vertices, withWait = true)
  }

  def addEdges(edges: Seq[S2EdgeLike]): Future[Seq[MutateResponse]] = {
    graph.mutateEdges(edges, withWait = true)
  }

  def getVertices(vertex: Seq[S2VertexLike]): Future[Seq[S2VertexLike]] = {
    graph.getVertices(vertex)
  }

  def getEdges(vertex: S2VertexLike, label: Label, _dir: String): Future[Seq[S2EdgeLike]] = {
    val dir = GraphUtil.directions(_dir)
    val labelWithDir = LabelWithDirection(label.id.get, dir)
    val step = Step(Seq(QueryParam(labelWithDir)))
    val q = Query(Seq(vertex), steps = Vector(step))

    graph.getEdges(q).map(_.edgeWithScores.map(_.edge))
  }

  def createService(args: Args): Try[Service] = {
    val serviceName = args.arg[String]("name")

    Service.findByName(serviceName) match {
      case Some(_) => Failure(new RuntimeException(s"Service (${serviceName}) already exists"))
      case None =>
        val cluster = args.argOpt[String]("cluster").getOrElse(parser.DefaultCluster)
        val hTableName = args.argOpt[String]("hTableName").getOrElse(s"${serviceName}-${parser.DefaultPhase}")
        val preSplitSize = args.argOpt[Int]("preSplitSize").getOrElse(1)
        val hTableTTL = args.argOpt[Int]("hTableTTL")
        val compressionAlgorithm = args.argOpt[String]("compressionAlgorithm").getOrElse(parser.DefaultCompressionAlgorithm)

        val serviceTry = management
          .createService(serviceName,
            cluster,
            hTableName,
            preSplitSize,
            hTableTTL,
            compressionAlgorithm)

        serviceTry
    }
  }

  def createServiceColumn(args: Args): Try[ServiceColumn] = {
    val serviceName = args.arg[String]("serviceName")
    val columnName = args.arg[String]("columnName")
    val columnType = args.arg[String]("columnType")
    val props = args.argOpt[Vector[Prop]]("props").getOrElse(Vector.empty)

    Try {
      management.createServiceColumn(serviceName, columnName, columnType, props)
    }
  }

  def deleteServiceColumn(args: Args): Try[ServiceColumn] = {
    val serviceColumnParam = args.arg[ServiceColumnParam]("service")

    val serviceName = serviceColumnParam.serviceName
    val columnName = serviceColumnParam.columnName

    Management.deleteColumn(serviceName, columnName)
  }

  def addPropsToLabel(args: Args): Try[Label] = {
    Try {
      val labelName = args.arg[String]("labelName")
      val props = args.arg[Vector[Prop]]("props").toList

      props.foreach { prop =>
        Management.addProp(labelName, prop).get
      }

      Label.findByName(labelName, false).get
    }
  }

  def addPropsToServiceColumn(args: Args): Try[ServiceColumn] = {
    Try {
      val serviceColumnParam = args.arg[ServiceColumnParam]("service")

      val serviceName = serviceColumnParam.serviceName
      val columnName = serviceColumnParam.columnName

      serviceColumnParam.props.foreach { prop =>
        Management.addVertexProp(serviceName, columnName, prop.name, prop.dataType, prop.defaultValue, prop.storeInGlobalIndex)
      }

      val src = Service.findByName(serviceName)
      ServiceColumn.find(src.get.id.get, columnName, false).get
    }
  }

  def createLabel(args: Args): Try[Label] = {
    val labelName = args.arg[String]("name")

    val srcServiceProp = args.arg[ServiceColumnParam]("sourceService")
    val srcServiceColumn = ServiceColumn.find(Service.findByName(srcServiceProp.serviceName).get.id.get, srcServiceProp.columnName).get
    val tgtServiceProp = args.arg[ServiceColumnParam]("targetService")
    val tgtServiceColumn = ServiceColumn.find(Service.findByName(tgtServiceProp.serviceName).get.id.get, tgtServiceProp.columnName).get

    val allProps = args.argOpt[Vector[Prop]]("props").getOrElse(Vector.empty)
    val indices = args.argOpt[Vector[Index]]("indices").getOrElse(Vector.empty)

    val serviceName = args.argOpt[String]("serviceName").getOrElse(tgtServiceColumn.service.serviceName)
    val consistencyLevel = args.argOpt[String]("consistencyLevel").getOrElse("weak")
    val hTableName = args.argOpt[String]("hTableName")
    val hTableTTL = args.argOpt[Int]("hTableTTL")
    val schemaVersion = args.argOpt[String]("schemaVersion").getOrElse(HBaseType.DEFAULT_VERSION)
    val isAsync = args.argOpt("isAsync").getOrElse(false)
    val compressionAlgorithm = args.argOpt[String]("compressionAlgorithm").getOrElse(parser.DefaultCompressionAlgorithm)
    val isDirected = args.argOpt[Boolean]("isDirected").getOrElse(true)
    //    val options = args.argOpt[String]("options") // TODO: support option type
    val options = Option("""{"storeVertex": true}""")

    val labelTry: scala.util.Try[Label] = management.createLabel(
      labelName,
      srcServiceProp.serviceName,
      srcServiceColumn.columnName,
      srcServiceColumn.columnType,
      tgtServiceProp.serviceName,
      tgtServiceColumn.columnName,
      tgtServiceColumn.columnType,
      isDirected,
      serviceName,
      indices,
      allProps,
      consistencyLevel,
      hTableName,
      hTableTTL,
      schemaVersion,
      isAsync,
      compressionAlgorithm,
      options
    )

    labelTry
  }

  def deleteLabel(args: Args): Try[Label] = {
    val labelName = args.arg[String]("name")

    Management.deleteLabel(labelName)
  }

  def allServices(): List[Service] = Service.findAll()

  def allServiceColumns(): List[ServiceColumn] = ServiceColumn.findAll()

  def findServiceByName(name: String): Option[Service] = Service.findByName(name)

  def allLabels() = Label.findAll()

  def findLabelByName(name: String): Option[Label] = Label.findByName(name)
}