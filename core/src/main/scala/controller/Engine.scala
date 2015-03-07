/** Copyright 2015 TappingStone, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *     http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package io.prediction.controller

import io.prediction.core.BaseDataSource
import io.prediction.core.BasePreparator
import io.prediction.core.BaseAlgorithm
import io.prediction.core.BaseServing
import io.prediction.core.Doer
import io.prediction.core.BaseEngine
import io.prediction.workflow.CreateWorkflow
import io.prediction.workflow.WorkflowUtils
import io.prediction.workflow.EngineLanguage
import io.prediction.workflow.PersistentModelManifest
import io.prediction.workflow.SparkWorkflowUtils
import io.prediction.workflow.StopAfterReadInterruption
import io.prediction.workflow.StopAfterPrepareInterruption
import io.prediction.data.storage.EngineInstance
import _root_.java.util.NoSuchElementException
import io.prediction.data.storage.StorageClientException

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import scala.language.implicitConversions

import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization.read

import io.prediction.workflow.NameParamsSerializer
import grizzled.slf4j.Logger

/** This class chains up the entire data process. PredictionIO uses this
  * information to create workflows and deployments. In Scala, you should
  * implement an object that extends the `IEngineFactory` trait similar to the
  * following example.
  *
  * {{{
  * object ItemRankEngine extends IEngineFactory {
  *   def apply() = {
  *     new Engine(
  *       classOf[ItemRankDataSource],
  *       classOf[ItemRankPreparator],
  *       Map(
  *         "knn" -> classOf[KNNAlgorithm],
  *         "rand" -> classOf[RandomAlgorithm],
  *         "mahoutItemBased" -> classOf[MahoutItemBasedAlgorithm]),
  *       classOf[ItemRankServing])
  *   }
  * }
  * }}}
  *
  * @see [[IEngineFactory]]
  * @tparam TD Training data class.
  * @tparam EI Evaluation info class.
  * @tparam PD Prepared data class.
  * @tparam Q Input query class.
  * @tparam P Output prediction class.
  * @tparam A Actual value class.
  * @param dataSourceClassMap Map of data source class.
  * @param preparatorClassMap Map of preparator class.
  * @param algorithmClassMap Map of algorithm names to classes.
  * @param servingClassMap Map of serving class.
  * @group Engine
  */
class Engine[TD, EI, PD, Q, P, A](
    val dataSourceClassMap: Map[String,
      Class[_ <: BaseDataSource[TD, EI, Q, A]]],
    val preparatorClassMap: Map[String, Class[_ <: BasePreparator[TD, PD]]],
    val algorithmClassMap: Map[String, Class[_ <: BaseAlgorithm[PD, _, Q, P]]],
    val servingClassMap: Map[String, Class[_ <: BaseServing[Q, P]]])
  extends BaseEngine[EI, Q, P, A] {

  implicit lazy val formats = Utils.json4sDefaultFormats +
    new NameParamsSerializer

  @transient lazy val logger = Logger[this.type]

  /**
    * @param dataSourceClass Data source class.
    * @param preparatorClass Preparator class.
    * @param algorithmClassMap Map of algorithm names to classes.
    * @param servingClass Serving class.
    */
  def this(
    dataSourceClass: Class[_ <: BaseDataSource[TD, EI, Q, A]],
    preparatorClass: Class[_ <: BasePreparator[TD, PD]],
    algorithmClassMap: Map[String, Class[_ <: BaseAlgorithm[PD, _, Q, P]]],
    servingClass: Class[_ <: BaseServing[Q, P]]) = this(
      Map("" -> dataSourceClass),
      Map("" -> preparatorClass),
      algorithmClassMap,
      Map("" -> servingClass)
    )

  /** Returns a new Engine instance. Mimic case class's copy method behavior.
    */
  def copy(
    dataSourceClassMap: Map[String, Class[_ <: BaseDataSource[TD, EI, Q, A]]]
      = dataSourceClassMap,
    preparatorClassMap: Map[String, Class[_ <: BasePreparator[TD, PD]]]
      = preparatorClassMap,
    algorithmClassMap: Map[String, Class[_ <: BaseAlgorithm[PD, _, Q, P]]]
      = algorithmClassMap,
    servingClassMap: Map[String, Class[_ <: BaseServing[Q, P]]]
      = servingClassMap): Engine[TD, EI, PD, Q, P, A] = {
    new Engine(
      dataSourceClassMap,
      preparatorClassMap,
      algorithmClassMap,
      servingClassMap)
  }

  /** Return persistentable models from trained model. */
  def train(
      sc: SparkContext,
      engineParams: EngineParams,
      engineInstanceId: String,
      params: WorkflowParams): Seq[Any] = {
    val (dataSourceName, dataSourceParams) = engineParams.dataSourceParams
    val dataSource = Doer(dataSourceClassMap(dataSourceName), dataSourceParams)

    val (preparatorName, preparatorParams) = engineParams.preparatorParams
    val preparator = Doer(preparatorClassMap(preparatorName), preparatorParams)

    val algoParamsList = engineParams.algorithmParamsList

    val algorithms = algoParamsList.map { case (algoName, algoParams) =>
      Doer(algorithmClassMap(algoName), algoParams)
    }

    val models = Engine.train(
      sc, dataSource, preparator, algorithms, params)

    val algoCount = algorithms.size
    val algoTuples: Seq[(String, Params, BaseAlgorithm[_, _, _, _], Any)] =
    (0 until algoCount).map { ax => {
      //val (name, params) = algoParamsList(ax)
      val (name, params) = algoParamsList(ax)
      (name, params, algorithms(ax), models(ax))
    }}

    makeSerializableModels(
      sc,
      engineInstanceId = engineInstanceId,
      algoTuples = algoTuples)
  }

  /** Algorithm models can be persisted before deploy. However, it is also
    * possible that models are not persisted. This method retrains non-persisted
    * models and return a list of model that can be used directly in deploy.
    */
  def prepareDeploy(
    sc: SparkContext,
    engineParams: EngineParams,
    engineInstanceId: String,
    persistedModels: Seq[Any],
    params: WorkflowParams): Seq[Any] = {

    val algoParamsList = engineParams.algorithmParamsList
    val algorithms = algoParamsList.map { case (algoName, algoParams) =>
      Doer(algorithmClassMap(algoName), algoParams)
    }

    val models = if (persistedModels.exists(m => m.isInstanceOf[Unit.type])) {
      // If any of persistedModels is Unit, we need to re-train the model.
      logger.info("Some persisted models are Unit, need to re-train.")
      val (dataSourceName, dataSourceParams) = engineParams.dataSourceParams
      val dataSource = Doer(dataSourceClassMap(dataSourceName), dataSourceParams)

      val (preparatorName, preparatorParams) = engineParams.preparatorParams
      val preparator = Doer(preparatorClassMap(preparatorName), preparatorParams)

      val td = dataSource.readTrainingBase(sc)
      val pd = preparator.prepareBase(sc, td)

      val models = algorithms.zip(persistedModels).map { case (algo, m) =>
        m match {
          case Unit => algo.trainBase(sc, pd)
          case _ => m
        }
      }
      models
    } else {
      logger.info("Using persisted model")
      persistedModels
    }

    models
    .zip(algorithms)
    .zip(algoParamsList)
    .zipWithIndex
    .map {
      case (((model, algo), (algoName, algoParams)), ax) => {
        model match {
          case modelManifest: PersistentModelManifest => {
            logger.info("Custom-persisted model detected for algorithm " +
              algo.getClass.getName)
            SparkWorkflowUtils.getPersistentModel(
              modelManifest,
              Seq(engineInstanceId, ax, algoName).mkString("-"),
              algoParams,
              Some(sc),
              getClass.getClassLoader)
          }
          case m => {
            try {
              logger.info(
                s"Loaded model ${m.getClass.getName} for algorithm " +
                s"${algo.getClass.getName}")
              m
            } catch {
              case e: NullPointerException =>
                logger.warn(
                  s"Null model detected for algorithm ${algo.getClass.getName}")
                m
            }
          }
        }  // model match
      }
    }
  }

  /** Extract model for persistent layer.
    *
    * PredictionIO presist models for future use.  It allows custom
    * implementation for persisting models. You need to implement the
    * [[io.prediction.controller.IPersistentModel]] interface. This method
    * traverses all models in the workflow. If the model is a
    * [[io.prediction.controller.IPersistentModel]], it calls the save method
    * for custom persistence logic.
    *
    * For model doesn't support custom logic, PredictionIO serializes the whole
    * model if the corresponding algorithm is local. On the other hand, if the
    * model is parallel (i.e. model associated with a number of huge RDDS), this
    * method return Unit, in which case PredictionIO will retrain the whole
    * model from scratch next time it is used.
    */
  def makeSerializableModels(
    sc: SparkContext,
    engineInstanceId: String,
    // AlgoName, Algo, Model
    algoTuples: Seq[(String, Params, BaseAlgorithm[_, _, _, _], Any)]
  ): Seq[Any] = {

    logger.info(s"engineInstanceId=$engineInstanceId")

    algoTuples
    .zipWithIndex
    .map { case ((name, params, algo, model), ax) =>
      algo.makePersistentModel(
        sc = sc,
        modelId = Seq(engineInstanceId, ax, name).mkString("-"),
        algoParams = params,
        bm = model)
    }
  }

  def eval(
    sc: SparkContext, 
    engineParams: EngineParams,
    params: WorkflowParams)
  : Seq[(EI, RDD[(Q, P, A)])] = {
    val (dataSourceName, dataSourceParams) = engineParams.dataSourceParams
    val dataSource = Doer(dataSourceClassMap(dataSourceName), dataSourceParams)

    val (preparatorName, preparatorParams) = engineParams.preparatorParams
    val preparator = Doer(preparatorClassMap(preparatorName), preparatorParams)

    val algoParamsList = engineParams.algorithmParamsList

    val algorithms = algoParamsList.map { case (algoName, algoParams) => {
      try {
        Doer(algorithmClassMap(algoName), algoParams)
      } catch {
        case e: NoSuchElementException => {
          if (algoName == "")
            logger.error("Empty algorithm name supplied but it could not " +
              "match with any algorithm in the engine's definition. " +
              "Existing algorithm name(s) are: " +
              s"${algorithmClassMap.keys.mkString(", ")}. Aborting.")
          else
            logger.error(s"${algoName} cannot be found in the engine's " +
              "definition. Existing algorithm name(s) are: " +
              s"${algorithmClassMap.keys.mkString(", ")}. Aborting.")
            sys.exit(1)
        }
      }
    }}

    val (servingName, servingParams) = engineParams.servingParams
    val serving = Doer(servingClassMap(servingName), servingParams)

    Engine.eval(sc, dataSource, preparator, algorithms, serving)
  }

  override
  def jValueToEngineParams(variantJson: JValue): EngineParams = {
    val engineLanguage = EngineLanguage.Scala
    // Extract EngineParams
    logger.info(s"Extracting datasource params...")
    val dataSourceParams: (String, Params) =
      WorkflowUtils.getParamsFromJsonByFieldAndClass(
        variantJson,
        "datasource",
        dataSourceClassMap,
        engineLanguage)
    logger.info(s"Datasource params: ${dataSourceParams}")

    logger.info(s"Extracting preparator params...")
    val preparatorParams: (String, Params) =
      WorkflowUtils.getParamsFromJsonByFieldAndClass(
        variantJson,
        "preparator",
        preparatorClassMap,
        engineLanguage)
    logger.info(s"Preparator params: ${preparatorParams}")

    val algorithmsParams: Seq[(String, Params)] =
      variantJson findField {
        case JField("algorithms", _) => true
        case _ => false
      } map { jv =>
        val algorithmsParamsJson = jv._2
        algorithmsParamsJson match {
          case JArray(s) => s.map { algorithmParamsJValue =>
            val eap = algorithmParamsJValue.extract[CreateWorkflow.AlgorithmParams]
            (
              eap.name,
              WorkflowUtils.extractParams(
                engineLanguage,
                compact(render(eap.params)),
                algorithmClassMap(eap.name))
            )
          }
          case _ => Nil
        }
      } getOrElse Seq(("", EmptyParams()))

    logger.info(s"Extracting serving params...")
    val servingParams: (String, Params) =
      WorkflowUtils.getParamsFromJsonByFieldAndClass(
        variantJson,
        "serving",
        servingClassMap,
        engineLanguage)
    logger.info(s"Serving params: ${servingParams}")

    new EngineParams(
      dataSourceParams = dataSourceParams,
      preparatorParams = preparatorParams,
      algorithmParamsList = algorithmsParams,
      servingParams = servingParams)
  }

  def engineInstanceToEngineParams(engineInstance: EngineInstance)
  : EngineParams = {
    implicit val formats = DefaultFormats
    val engineLanguage = EngineLanguage.Scala

    val dataSourceParamsWithName: (String, Params) = {
      val (name, params) =
        read[(String, JValue)](engineInstance.dataSourceParams)
      if (!dataSourceClassMap.contains(name)) {
        logger.error(s"Unable to find datasource class with name '${name}'" +
          " defined in Engine.")
        sys.exit(1)
      }
      val extractedParams = WorkflowUtils.extractParams(
        engineLanguage,
        compact(render(params)),
        dataSourceClassMap(name))
      (name, extractedParams)
    }

    val preparatorParamsWithName: (String, Params) = {
      val (name, params) =
        read[(String, JValue)](engineInstance.preparatorParams)
      if (!preparatorClassMap.contains(name)) {
        logger.error(s"Unable to find preparator class with name '${name}'" +
          " defined in Engine.")
        sys.exit(1)
      }
      val extractedParams = WorkflowUtils.extractParams(
        engineLanguage,
        compact(render(params)),
        preparatorClassMap(name))
      (name, extractedParams)
    }

    val algorithmsParamsWithNames =
      read[Seq[(String, JValue)]](engineInstance.algorithmsParams).map {
        case (algoName, params) =>
          val extractedParams = WorkflowUtils.extractParams(
            engineLanguage,
            compact(render(params)),
            algorithmClassMap(algoName))
          (algoName, extractedParams)
      }

    val servingParamsWithName: (String, Params) = {
      val (name, params) = read[(String, JValue)](engineInstance.servingParams)
      if (!servingClassMap.contains(name)) {
        logger.error(s"Unable to find serving class with name '${name}'" +
          " defined in Engine.")
        sys.exit(1)
      }
      val extractedParams = WorkflowUtils.extractParams(
        engineLanguage,
        compact(render(params)),
        servingClassMap(name))
      (name, extractedParams)
    }

    new EngineParams(
      dataSourceParams = dataSourceParamsWithName,
      preparatorParams = preparatorParamsWithName,
      algorithmParamsList = algorithmsParamsWithNames,
      servingParams = servingParamsWithName)
  }
}

object Engine {
  type EX = Int
  type AX = Int
  type QX = Long

  @transient lazy val logger = Logger[this.type]

  class DataSourceMap[TD, EI, Q, A](
    val m: Map[String, Class[_ <: BaseDataSource[TD, EI, Q, A]]]) {
    def this(c: Class[_ <: BaseDataSource[TD, EI, Q, A]]) = this(Map("" -> c))
  }

  object DataSourceMap {
    implicit def cToMap[TD, EI, Q, A](
      c: Class[_ <: BaseDataSource[TD, EI, Q, A]]):
      DataSourceMap[TD, EI, Q, A] = new DataSourceMap(c)
    implicit def mToMap[TD, EI, Q, A](
      m: Map[String, Class[_ <: BaseDataSource[TD, EI, Q, A]]]):
      DataSourceMap[TD, EI, Q, A] = new DataSourceMap(m)
  }

  class PreparatorMap[TD, PD](
    val m: Map[String, Class[_ <: BasePreparator[TD, PD]]]) {
    def this(c: Class[_ <: BasePreparator[TD, PD]]) = this(Map("" -> c))
  }

  object PreparatorMap {
    implicit def cToMap[TD, PD](
      c: Class[_ <: BasePreparator[TD, PD]]):
      PreparatorMap[TD, PD] = new PreparatorMap(c)
    implicit def mToMap[TD, PD](
      m: Map[String, Class[_ <: BasePreparator[TD, PD]]]):
      PreparatorMap[TD, PD] = new PreparatorMap(m)
  }

  class ServingMap[Q, P](
    val m: Map[String, Class[_ <: BaseServing[Q, P]]]) {
    def this(c: Class[_ <: BaseServing[Q, P]]) = this(Map("" -> c))
  }

  object ServingMap {
    implicit def cToMap[Q, P](
      c: Class[_ <: BaseServing[Q, P]]): ServingMap[Q, P] =
        new ServingMap(c)
    implicit def mToMap[Q, P](
      m: Map[String, Class[_ <: BaseServing[Q, P]]]): ServingMap[Q, P] =
        new ServingMap(m)
  }

  def apply[TD, EI, PD, Q, P, A](
    dataSourceMap: DataSourceMap[TD, EI, Q, A],
    preparatorMap: PreparatorMap[TD, PD],
    algorithmClassMap: Map[String, Class[_ <: BaseAlgorithm[PD, _, Q, P]]],
    servingMap: ServingMap[Q, P]) = new Engine(
      dataSourceMap.m,
      preparatorMap.m,
      algorithmClassMap,
      servingMap.m
    )

  def train[TD, PD, Q](
      sc: SparkContext,
      dataSource: BaseDataSource[TD, _, Q, _],
      preparator: BasePreparator[TD, PD],
      algorithmList: Seq[BaseAlgorithm[PD, _, Q, _]],
      params: WorkflowParams
    ): Seq[Any] = {
    logger.info("EngineWorkflow.train")
    logger.info(s"DataSource: $dataSource")
    logger.info(s"Preparator: $preparator")
    logger.info(s"AlgorithmList: $algorithmList")

    if (params.skipSanityCheck)
      logger.info("Data sanity check is off.")
    else
      logger.info("Data santiy check is on.")

    val td = try {
      dataSource.readTrainingBase(sc)
    } catch {
      case e: StorageClientException =>
        logger.error(s"Error occured reading from data source. (Reason: " +
          e.getMessage + ") Please see the log for debugging details.", e)
        sys.exit(1)
    }

    if (!params.skipSanityCheck) {
      td match {
        case sanityCheckable: SanityCheck => {
          logger.info(s"${td.getClass.getName} supports data sanity" +
            " check. Performing check.")
          sanityCheckable.sanityCheck()
        }
        case _ => {
          logger.info(s"${td.getClass.getName} does not support" +
            " data sanity check. Skipping check.")
        }
      }
    }

    if (params.stopAfterRead) {
      logger.info("Stopping here because --stop-after-read is set.")
      throw StopAfterReadInterruption()
    }

    val pd = preparator.prepareBase(sc, td)

    if (!params.skipSanityCheck) {
      pd match {
        case sanityCheckable: SanityCheck => {
          logger.info(s"${pd.getClass.getName} supports data sanity" +
            " check. Performing check.")
          sanityCheckable.sanityCheck()
        }
        case _ => {
          logger.info(s"${pd.getClass.getName} does not support" +
            " data sanity check. Skipping check.")
        }
      }
    }

    if (params.stopAfterPrepare) {
      logger.info("Stopping here because --stop-after-prepare is set.")
      throw StopAfterPrepareInterruption()
    }

    val models: Seq[Any] = algorithmList.map(_.trainBase(sc, pd))

    if (!params.skipSanityCheck) {
      models.foreach { model => {
        model match {
          case sanityCheckable: SanityCheck => {
            logger.info(s"${model.getClass.getName} supports data sanity" +
              " check. Performing check.")
            sanityCheckable.sanityCheck()
          }
          case _ => {
            logger.info(s"${model.getClass.getName} does not support" +
              " data sanity check. Skipping check.")
          }
        }
      }}
    }

    logger.info("EngineWorkflow.train completed")
    models
  }

  def eval[TD, PD, Q, P, A, EI](
      sc: SparkContext,
      dataSource: BaseDataSource[TD, EI, Q, A],
      preparator: BasePreparator[TD, PD],
      algorithmList: Seq[BaseAlgorithm[PD, _, Q, P]],
      serving: BaseServing[Q, P]): Seq[(EI, RDD[(Q, P, A)])] = {
    logger.info(s"DataSource: $dataSource")
    logger.info(s"Preparator: $preparator")
    logger.info(s"AlgorithmList: $algorithmList")
    logger.info(s"Serving: $serving")

    val algoMap: Map[AX, BaseAlgorithm[PD, _, Q, P]] = algorithmList
      .zipWithIndex
      .map(_.swap)
      .toMap
    val algoCount = algoMap.size

    val evalTupleMap: Map[EX, (TD, EI, RDD[(Q, A)])] = dataSource
      .readEvalBase(sc)
      .zipWithIndex
      .map(_.swap)
      .toMap

    val evalCount = evalTupleMap.size

    val evalTrainMap: Map[EX, TD] = evalTupleMap.mapValues(_._1)
    val evalInfoMap: Map[EX, EI] = evalTupleMap.mapValues(_._2)
    val evalQAsMap: Map[EX, RDD[(QX, (Q, A))]] = evalTupleMap
      .mapValues(_._3)
      .mapValues{ _.zipWithUniqueId().map(_.swap) }

    val preparedMap: Map[EX, PD] = evalTrainMap.mapValues { td => {
      preparator.prepareBase(sc, td)
    }}

    val algoModelsMap: Map[EX, Map[AX, Any]] = preparedMap.mapValues { pd => {
      algoMap.mapValues(_.trainBase(sc,pd))
    }}

    val algoPredictsMap: Map[EX, RDD[(QX, Seq[P])]] = (0 until evalCount)
    .map { ex => {
      val modelMap: Map[AX, Any] = algoModelsMap(ex)

      val qs: RDD[(QX, Q)] = evalQAsMap(ex).mapValues(_._1)

      val algoPredicts: Seq[RDD[(QX, (AX, P))]] = (0 until algoCount)
      .map { ax => {
        val algo = algoMap(ax)
        val model = modelMap(ax)
        val rawPredicts: RDD[(QX, P)] = algo.batchPredictBase(sc, model, qs)
        val predicts: RDD[(QX, (AX, P))] = rawPredicts.map { case (qx, p) => {
          (qx, (ax, p))
        }}
        predicts
      }}

      val unionAlgoPredicts: RDD[(QX, Seq[P])] = sc.union(algoPredicts)
      .groupByKey
      .mapValues { ps => {
        assert (ps.size == algoCount, "Must have same length as algoCount")
        // TODO. Check size == algoCount
        ps.toSeq.sortBy(_._1).map(_._2)
      }}

      (ex, unionAlgoPredicts)
    }}
    .toMap

    val servingQPAMap: Map[EX, RDD[(Q, P, A)]] = algoPredictsMap
    .map { case (ex, psMap) => {
      val qasMap: RDD[(QX, (Q, A))] = evalQAsMap(ex)
      val qpsaMap: RDD[(QX, Q, Seq[P], A)] = psMap.join(qasMap)
      .map { case (qx, t) => (qx, t._2._1, t._1, t._2._2) }

      val qpaMap: RDD[(Q, P, A)] = qpsaMap.map {
        case (qx, q, ps, a) => (q, serving.serveBase(q, ps), a)
      }
      (ex, qpaMap)
    }}

    (0 until evalCount).map { ex => {
      (evalInfoMap(ex), servingQPAMap(ex))
    }}
    .toSeq
  }





}
/** This class serves as a logical grouping of all required engine's parameters.
  *
  * @param dataSourceParams Data Source name-parameters tuple.
  * @param preparatorParams Preparator name-parameters tuple.
  * @param algorithmParamsList List of algorithm name-parameter pairs.
  * @param servingParams Serving name-parameters tuple.
  * @group Engine
  */
class EngineParams(
    val dataSourceParams: (String, Params) = ("", EmptyParams()),
    val preparatorParams: (String, Params) = ("", EmptyParams()),
    val algorithmParamsList: Seq[(String, Params)] = Seq(),
    val servingParams: (String, Params) = ("", EmptyParams()))
  extends Serializable {

  // A case class style copy method.
  def copy(
    dataSourceParams: (String, Params) = dataSourceParams,
    preparatorParams: (String, Params) = preparatorParams,
    algorithmParamsList: Seq[(String, Params)] = algorithmParamsList,
    servingParams: (String, Params) = servingParams): EngineParams = {

    new EngineParams(
      dataSourceParams,
      preparatorParams,
      algorithmParamsList,
      servingParams)
  }
}

/** Companion object for creating [[EngineParams]] instances.
  *
  * @group Engine
  */
object EngineParams {
  /** Create EngineParams.
    *
    * @param dataSourceName Data Source name
    * @param dataSourceParams Data Source parameters
    * @param preparatorName Preparator name
    * @param preparatorParams Preparator parameters
    * @param algorithmParamsList List of algorithm name-parameter pairs.
    * @param servingName Serving name
    * @param servingParams Serving parameters
    */
  def apply(
    dataSourceName: String = "",
    dataSourceParams: Params = EmptyParams(),
    preparatorName: String = "",
    preparatorParams: Params = EmptyParams(),
    algorithmParamsList: Seq[(String, Params)] = Seq(),
    servingName: String = "",
    servingParams: Params = EmptyParams()): EngineParams = {
      new EngineParams(
        dataSourceParams = (dataSourceName, dataSourceParams),
        preparatorParams = (preparatorName, preparatorParams),
        algorithmParamsList = algorithmParamsList,
        servingParams = (servingName, servingParams)
      )
    }
}

/** SimpleEngine has only one algorithm, and uses default preparator and serving
  * layer. Current default preparator is `IdentityPreparator` and serving is
  * `FirstServing`.
  *
  * @tparam TD Training data class.
  * @tparam EI Evaluation info class.
  * @tparam Q Input query class.
  * @tparam P Output prediction class.
  * @tparam A Actual value class.
  * @param dataSourceClass Data source class.
  * @param algorithmClass of algorithm names to classes.
  * @group Engine
  */
class SimpleEngine[TD, EI, Q, P, A](
    dataSourceClass: Class[_ <: BaseDataSource[TD, EI, Q, A]],
    algorithmClass: Class[_ <: BaseAlgorithm[TD, _, Q, P]])
  extends Engine(
    dataSourceClass,
    IdentityPreparator(dataSourceClass),
    Map("" -> algorithmClass),
    LFirstServing(algorithmClass))

/** This shorthand class serves the `SimpleEngine` class.
  *
  * @param dataSourceParams Data source parameters.
  * @param algorithmParams List of algorithm name-parameter pairs.
  * @group Engine
  */
class SimpleEngineParams(
    dataSourceParams: Params = EmptyParams(),
    algorithmParams: Params = EmptyParams())
  extends EngineParams(
    dataSourceParams = ("", dataSourceParams),
    algorithmParamsList = Seq(("", algorithmParams)))

/** If you intend to let PredictionIO create workflow and deploy serving
  * automatically, you will need to implement an object that extends this trait
  * and return an [[Engine]].
  *
  * @group Engine
  */
trait IEngineFactory {
  /** Creates an instance of an [[Engine]]. */
  def apply(): BaseEngine[_, _, _, _]

  /** Override this method to programmatically return engine parameters. */
  def engineParams(key: String): EngineParams = EngineParams()
}


/** Defines an engine parameters generator.
  *
  * Implementations of this trait can be supplied to "pio eval" as the second
  * argument.
  *
  * @group Evaluation
  */
trait EngineParamsGenerator {
  protected[this] var epList: Seq[EngineParams] = _
  protected[this] var epListSet: Boolean = false

  /** Returns the list of [[EngineParams]] of this [[EngineParamsGenerator]]. */
  def engineParamsList: Seq[EngineParams] = {
    assert(epListSet, "EngineParamsList not set")
    epList
  }

  /** Sets the list of [[EngineParams]] of this [[EngineParamsGenerator]]. */
  def engineParamsList_=(l: Seq[EngineParams]) {
    assert(!epListSet, "EngineParamsList can bet set at most once")
    epList = Seq(l:_*)
    epListSet = true
  }
}


/** Mix in this trait for queries that contain prId (PredictedResultId).
  * This is useful when your engine expects queries to also be associated with
  * prId keys when feedback loop is enabled.
  *
  * @group General
  */
trait WithPrId {
  val prId: String = ""
}
