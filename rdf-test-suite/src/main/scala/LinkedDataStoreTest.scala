package org.w3.banana

import org.scalatest._
import org.scalatest.matchers.MustMatchers
import scalaz._
import scalaz.Scalaz._
import java.util.UUID
import org.w3.banana.LinkedDataStore._
import akka.actor.ActorSystem
import akka.util.Timeout
import org.w3.banana.util._

abstract class LinkedDataStoreTest[Rdf <: RDF](
  syncStore: RDFStore[Rdf])(
    implicit diesel: Diesel[Rdf])
    extends WordSpec with MustMatchers {

  import diesel._

  val system = ActorSystem("jena-asynsparqlquery-test", util.AkkaDefaults.DEFAULT_CONFIG)
  implicit val timeout = Timeout(1000)
  val store = AsyncRDFStore(syncStore, system)

  val objects = new ObjectExamples
  import objects._

  val address1 = VerifiedAddress("32 Vassar st", City("Cambridge"))
  val address2 = VerifiedAddress("rue des poissons", City("Paris"))
  val person = Person("betehess")

  class LinkedPerson(uri: Rdf#URI, store: AsyncGraphStore[Rdf])(implicit diesel: Diesel[Rdf]) {

    lazy val bananaResource: BananaFuture[LinkedDataResource[Rdf]] = store.get(uri)

    def getAddresses(): BananaFuture[Set[Address]] = {
      for {
        ldr <- bananaResource
        uris <- (ldr.resource / Person.address).asSet[Rdf#URI].bf
        resources <- store.get(uris)
        addresses <- resources.map(_.resource.as[Address]).sequence[BananaValidation, Address].bf
      } yield {
        addresses
      }
    }

    def linkAddress(addressURI: Rdf#URI): BananaFuture[Unit] =
      store.append(uri, uri -- foaf("address") ->- addressURI)

  }

  def linkedPerson(ldr: LinkedDataResource[Rdf]): LinkedPerson =
    new LinkedPerson(ldr.uri, store) {
      import org.w3.banana.util._
      // just a little optimization in the case we already have the resource at hand
      override lazy val bananaResource = ldr.bf
    }

  def linkedPerson(uri: Rdf#URI): LinkedPerson = new LinkedPerson(uri, store)

  "saving a person, 2 addresses and then retrieve them" in {

    val r = for {
      personUri <- store.post(Person.container, person.toPG)
      lp = linkedPerson(personUri)
      address1Uri <- store.post(personUri, address1.toPG)
      address2Uri <- store.post(personUri, address2.toPG)
      _ <- lp.linkAddress(address1Uri)
      _ <- lp.linkAddress(address2Uri)
      personResource <- store.get(personUri)
      addresses <- linkedPerson(personResource).getAddresses()
    } yield {
      addresses
    }

    val addresses = r.await().map(_.toSet)
    addresses must be(Success(Set(address1, address2)))

  }

}
