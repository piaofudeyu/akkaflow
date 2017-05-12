package com.kent.main

//import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.ask
import akka.pattern.pipe
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import scala.io.StdIn
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.cluster.ClusterEvent._
import com.kent.main.ClusterRole.Registration
import akka.actor.Props
import akka.actor.RootActorPath
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.Http.ServerBinding
import com.kent.pub.Event._

class HttpServer extends ClusterRole {
  implicit val timeout = Timeout(20 seconds)
  
  private def getResponseFromWorkflowManager(event: Any): ResponseData = {
    val wfm = context.actorSelection(roler(0).path / "wfm")
    val resultF = (wfm ? event).mapTo[ResponseData]
    val result = Await.result(resultF, 20 second)
    result
  }
  private def getResponseFromCoordinatorManager(event: Any): ResponseData = {
    val cm = context.actorSelection(roler(0).path / "cm")
    val resultF = (cm ? event).mapTo[ResponseData]
    val result = Await.result(resultF, 20 second)
    result
  }
  private def getResponseFromMaster(event: Any): ResponseData = {
    val master = context.actorSelection(roler(0).path)
    val resultF = (master ? event).mapTo[ResponseData]
    val result = Await.result(resultF, 20 second)
    result
  }
  
  def receive: Actor.Receive = {
    case MemberUp(member) => 
    case UnreachableMember(member) =>
    case MemberRemoved(member, previousStatus) =>
    case state: CurrentClusterState =>
    case _:MemberEvent => // ignore 
    case Registration() => {
      //worker请求注册
      context watch sender
      roler = roler :+ sender
      log.info("注册master角色: " + sender)
    }
    case event@ShutdownCluster() => sender ! getResponseFromMaster(event)
                              Thread.sleep(5000)
                              HttpServer.shutdwon()
    case event@CollectClusterInfo() => sender ! getResponseFromMaster(event)
    case event@RemoveWorkFlow(_) => sender ! getResponseFromWorkflowManager(event)
    case event@AddWorkFlow(_) => sender ! getResponseFromWorkflowManager(event)
    case event@ReRunWorkflowInstance(_) => sender ! getResponseFromWorkflowManager(event)
    case event@KillWorkFlowInstance(_) => sender ! getResponseFromWorkflowManager(event)
    case event@AddCoor(_) => sender ! getResponseFromCoordinatorManager(event)
    case event@RemoveCoor(_) => sender ! getResponseFromCoordinatorManager(event)
    case event@ManualNewAndExecuteWorkFlowInstance(_, _) => sender ! getResponseFromWorkflowManager(event)
      
  }
}
object HttpServer extends App{
  import org.json4s._
  import org.json4s.jackson.Serialization
  import org.json4s.jackson.Serialization.{read, write}
  implicit val formats = Serialization.formats(NoTypeHints)
  implicit val timeout = Timeout(20 seconds)
  def props = Props[HttpServer]
  val defaultConf = ConfigFactory.load()
  val httpConf = defaultConf.getStringList("workflow.nodes.http-servers").get(0).split(":")
  
  // 创建一个Config对象
  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + httpConf(1))
      .withFallback(ConfigFactory.parseString("akka.remote.netty.tcp.hostname=" + httpConf(0)))
      .withFallback(ConfigFactory.parseString("akka.cluster.roles = [http-server]"))
      .withFallback(defaultConf)
  
  implicit val system = ActorSystem("akkaflow", config)
  val curActorSystem = system
  implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.dispatcher
  val httpServer = system.actorOf(HttpServer.props,"http-server")
  
  private def handleRequestWithActor(event: Any): Route = {
    val data = (httpServer ? event).mapTo[ResponseData] 
	  onSuccess(data){ x =>
	    complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, write(x)))
    }
  }
  private def handleRequestWithResponseData(result: String, msg: String, data: String): Route = {
     complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, write(ResponseData(result,msg,data))))   
  }
  
   //workflow
  val wfRoute =  path("akkaflow" / "workflow" / Segment){ 
      name => 
        parameter('action){
        action => {
          if(action == "del"){
            handleRequestWithActor(RemoveWorkFlow(name))
          }else if(action == "get"){
            handleRequestWithResponseData("fail","暂时还没get方法",null)   
          }else if(action == "run"){
             parameterMap {
               paras => handleRequestWithActor(ManualNewAndExecuteWorkFlowInstance(name, paras))
             }
          }
          else{
        	  handleRequestWithResponseData("fail","action参数有误",null)                      
          }
        }
      }
    } ~ path("akkaflow" / "workflow"){
      post {
        formField('content){ content =>
          parameter('action){ action => {
            	if(action == "add" && content != null && content.trim() != ""){
            	  handleRequestWithActor(AddWorkFlow(content))  
            	}else{
            	 handleRequestWithResponseData("fail","action参数有误",null)    
            	}
            }
          }
        }
      }
    }
    //coordinator
    val coorRoute = path("akkaflow" / "coor" / Segment){           
      name => parameter('action){
        action => {
          if(action == "del"){
           handleRequestWithActor(RemoveCoor(name))                    
          }else if(action == "get"){
        	  handleRequestWithResponseData("fail","暂时还没get方法",null)                      
          }else{
        	  handleRequestWithResponseData("fail","action参数有误",null)                     
          }
        }
      }
    } ~ path("akkaflow" / "coor"){
      post{
        formField('content){ content =>
          parameter('action){
            action => {
              if(action == "add" && content != null && content.trim() != ""){
            	  handleRequestWithActor(AddCoor(content)) 	  
            	}else{
            	  handleRequestWithResponseData("fail","action参数有误",null)    
            	}
            }
          }
        }
      }
    }
    //workflow instance
    val wfiRoute = path("akkaflow" / "workflow" / "instance" / Segment){            
      id => parameter('action){
        action => {
          if(action == "rerun"){
            handleRequestWithActor(ReRunWorkflowInstance(id))
          }else if(action == "kill"){
        	  handleRequestWithActor(KillWorkFlowInstance(id))                     
          }else{
        	  handleRequestWithActor("fail","action参数有误",null)                     
          }
        }
      }
    }
    //cluster
    val clusterRoute = path("akkaflow" / "cluster"){            
      parameter('action){
        action => {
          if(action == "shutdown"){
            handleRequestWithActor(ShutdownCluster())
          }else if(action == "get"){
            handleRequestWithActor(CollectClusterInfo())
          }else{
        	  handleRequestWithActor("fail","action参数有误",null)                     
          }
        }
      }
    }
    
    val route = wfRoute ~ coorRoute ~ wfiRoute ~ clusterRoute
  
   val bindingFuture = Http().bindAndHandle(route, "localhost", 8090)
    def shutdwon(){
      bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())
    }
}