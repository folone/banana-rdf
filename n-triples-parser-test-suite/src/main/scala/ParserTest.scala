package org.w3.rdf

import org.junit.Test
import org.junit.Assert._
import util.Random
import java.io._
import nomo.{Success, Accumulator}
import com.hp.hpl.jena.rdf.model.{ModelFactory=>JenaModelFactory, Model => JenaModel}

// would be happy to use
// NTriplesParserTest[M <: Model](m: M, parser: NTriplesParser[m.type], isomorphism: GraphIsomorphism[m.type])
// but the compiler complains, saying it does not know m
abstract class NTriplesParserTest[M <: Module, F, E, X](val parser: NTriplesParser[M, F, E, X, Unit]) {

  implicit val U: Unit = ()
  val isomorphism: GraphIsomorphism[parser.m.type]
  
  import parser.m._
  import isomorphism._

  /** so that this test can be run with different IO models */
  def toF(string: String): F

  val n3 ="""
  <http://www.w3.org/2001/sw/RDFCore/ntriples/> <http://purl.org/dc/elements/1.1/creator> "Dave Beckett" .
  <http://www.w3.org/2001/sw/RDFCore/ntriples/> <http://purl.org/dc/elements/1.1/creator> "Art Barstow" .

 <http://www.w3.org/2001/sw/RDFCore/ntriples/> <http://purl.org/dc/elements/1.1/publisher> <http://www.w3.org/> .

 """
  
  @Test()
  def read_simple_n3(): Unit = {

    val res = parser.ntriples(n3).get
    val parsedGraph = parser.m.Graph(res)
    assertEquals("should be three triples in graph",3,parsedGraph.size)

    val ntriples = IRI("http://www.w3.org/2001/sw/RDFCore/ntriples/")
    val creator = IRI("http://purl.org/dc/elements/1.1/creator")
    val publisher = IRI("http://purl.org/dc/elements/1.1/publisher")
    val dave = TypedLiteral("Dave Beckett", xsdStringIRI)
    val art = TypedLiteral("Art Barstow")
    val w3org = IRI("http://www.w3.org/")
    
    val expected = 
      Graph(
        Triple(ntriples, creator, dave),
        Triple(ntriples, creator, art),
        Triple(ntriples, publisher, w3org)
      )
//    assertEquals("The two graphs must have the same size",expected.size,parsedGraph.size)

    assertTrue("graphs must be isomorphic",isIsomorphicWith(expected, parsedGraph))
  }

  @Test()
  def read_long_n3s_in_chunks(): Unit = {
    import scala.io._

    def randomSz = {
      val random = 29 to 47+1
      random(Random.nextInt(random.length ))
    }
    val card: File = new File(this.getClass.getResource("/card.nt").toURI)
    val in = new FileInputStream(card)
    val bytes = new Array[Byte](randomSz)

    val card_random: File = new File(this.getClass.getResource("/card.random.nt").toURI)
    val inR = new FileInputStream(card_random)
    val bytesR = new Array[Byte](randomSz)

    val jenaCard = JenaModelFactory.createDefaultModel()
    jenaCard.read(new FileInputStream(card),null,"N-TRIPLE")
    assertEquals("Pure Jena should have read 354 triples in"+card.getPath,354,jenaCard.size())


    import parser.P._
    case class ParsedChunk(val parser: Parser[List[Triple]],val acc: Accumulator[Char, X, Unit]) {
      def parse(buf: Seq[Char]) = {
        if (!buf.isEmpty) {
          val (tripleParser, newAccu) = parser.feedChunked(buf, acc, buf.size)
          ParsedChunk(tripleParser, newAccu)
        } else {
          this
        }
      }
    }

    var chunk = ParsedChunk(parser.ntriples,parser.P.annotator())
    var chunkR = ParsedChunk(parser.ntriples,parser.P.annotator())

    //scala.io.Reader seemed to have a problem.
    var inOpen = true
    var inROpen = true
    while (inOpen || inROpen) {
      if (inOpen) {
        try {
          val length = in.read(bytes)
          if (length > -1) {
            chunk = chunk.parse(new String(bytes, 0, length, "ASCII"))
          } else inOpen = false
        } catch {
          case e: IOException => inOpen = false
        }
      }
      if (inROpen) {
        try {
          val length = inR.read(bytesR)
          if (length > -1) {
            chunkR = chunkR.parse(new String(bytesR, 0, length, "ASCII"))
          } else inROpen = false
        } catch {
          case e: IOException => inROpen = false
        }
      }
    }



    val res = chunk.parser.result(chunk.acc)
    val resR = chunkR.parser.result(chunkR.acc)

    println("the last triple found was in card.nt was "+res.get.last)
    println("the last triple found was in card.random.nt was "+resR.get.last)

    assertNotSame("the results of reading both cards should be different lists",res.get,resR.get)

    assertTrue("error parsing card.nt - failed at "+res.position+" status="+res.status,res.isSuccess)
    assertTrue("error parsing card.random.nt - failed at "+res.position+" with status "+res.status,resR.isSuccess)


    val g = parser.m.Graph(res.get)
    val gR = parser.m.Graph(resR.get)

    println("<<< "+diff(g, gR).size)
    println(">>> "+diff(gR, g).size)

    assertEquals("There should be 354 triples in "+card.getPath,354,g.size)
    assertEquals("There should be 354 triples in "+card_random.getPath,354,gR.size)

    assertTrue("the two graphs must be isomorphic",isIsomorphicWith(g,gR))

  }


}