package im.actor.server.migrations

import java.time.Instant

import akka.actor.{ ActorSystem, Props }
import akka.http.scaladsl.util.FastFuture
import akka.persistence.{ PersistentActor, RecoveryCompleted }
import im.actor.concurrent.FutureExt
import im.actor.server.db.DbExtension
import im.actor.server.persist.contact.UserContactRepo
import im.actor.server.user.UserEvents

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

object LocalNamesFromKVMigrator extends Migration {

  override protected def migrationName: String = "2015-11-03-LocalNamesFromKVMigration"

  override protected def migrationTimeout: Duration = 1.hour

  override protected def startMigration()(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher
    system.log.warning("Migrating local names from KV")

    DbExtension(system).db.run(UserContactRepo.fetchAll)
      .flatMap { contacts ⇒
        FutureExt.ftraverse(contacts) { contact ⇒
          for {
            _ ← migrateSingle(contact.ownerUserId, contact.contactUserId, contact.name)
          } yield ()
        }
      }
      .map(_ ⇒ ())
  }

  private def migrateSingle(ownerUserId: Int, contactUserId: Int, localNameOpt: Option[String])(implicit system: ActorSystem): Future[Unit] = {
    localNameOpt match {
      case Some(localName) ⇒
        system.log.debug("Moving contact of user: {}, {} ({})", ownerUserId, localName, contactUserId)
        val promise = Promise[Unit]
        system.actorOf(Props(new LocalNamesFromKVMigrator(promise, ownerUserId, contactUserId, localName)))
        promise.future
      case None ⇒
        system.log.debug("Not moving contact of user: {} ({})", ownerUserId, contactUserId)
        FastFuture.successful(())
    }
  }
}

private case object Migrate

private final class LocalNamesFromKVMigrator(promise: Promise[Unit], ownerUserId: Int, contactUserId: Int, localName: String) extends PersistentActor {

  import UserEvents._

  override def persistenceId: String = s"User_${ownerUserId}_Contacts"

  override def receiveCommand = {
    case Migrate ⇒
      persist(LocalNameChanged(Instant.now(), contactUserId, Some(localName))) { _ ⇒
        promise.success(())
        context stop self
      }
  }

  override def receiveRecover = {
    case RecoveryCompleted ⇒ self ! Migrate
  }

  override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
    super.onRecoveryFailure(cause, event)
    promise.failure(cause)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    promise.failure(reason)
  }
}