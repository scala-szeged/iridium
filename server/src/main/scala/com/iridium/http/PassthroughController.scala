package com.iridium
package http

import cats.*
import cats.effect.*
import cats.syntax.all.*
import com.iridium.client.AsteroidClient
import com.iridium.database.Favourites
import com.iridium.domain.*
import io.circe.generic.auto.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.QueryParamDecoderMatcher
import org.http4s.server.Router
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.util.Try

class PassthroughController[F[_]: Async] private (
    client: AsteroidClient[F],
    favourites: Favourites[F]
) extends Http4sDsl[F] {

  private val prefix = "/passthrough"

  private val logger = Slf4jLogger.getLogger[F]

  implicit val localDateQueryParamDecoder: QueryParamDecoder[LocalDate] =
    QueryParamDecoder[String].emap { str =>
      Try(LocalDate.parse(str, DateTimeFormatter.ISO_DATE)).toEither
        .leftMap(t => ParseFailure("Invalid date format", t.getMessage))
    }

  private object StartDateQueryParamMatcher
      extends QueryParamDecoderMatcher[LocalDate]("start_date")
  private object EndDateQueryParamMatcher extends QueryParamDecoderMatcher[LocalDate]("end_date")

  // get /passthrough/search_by_range/
  private val searchByRange: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "search_by_range"
        :? StartDateQueryParamMatcher(startDate)
        +& EndDateQueryParamMatcher(endDate) =>
      for {
        ranges <- Monad[F].pure(logic.makeMax7DaysRanges(startDate, endDate))
        listOfList <- ranges.flatTraverse { case (startDate, endDate) =>
          for {
            _            <- logger.info(s"client.searchByRange($startDate, $endDate)")
            asteroidList <- client.searchByRange(startDate, endDate)
            asteroidsMaybe = asteroidList.near_earth_objects.map { neosByDate =>
              neosByDate
                .flatMap((_, neos) =>
                  neos.map { neo =>
                    AsteroidOutput(name = neo.name, id = neo.id)
                  }
                )
                .toList
            }
            _ <-
              if asteroidsMaybe.isEmpty then
                logger.error(
                  s"Got ${asteroidList.code} ${asteroidList.http_error}: ${asteroidList.error_message} for the request ${asteroidList.request}"
                )
              else
                logger.debug(
                  s"Got ${asteroidsMaybe.get.size} asteroids for the request ${asteroidList.request}"
                )
          } yield asteroidsMaybe.toList
        }
        response <- Ok(listOfList.flatten.sortBy(_.name))
      } yield response
  }

  // get /passthrough/detailsOf/{asteroidId}
  private val detailsOf: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "details_of" / IntVar(asteroidId) =>
      for {
        asteroidDetails <- client.detailsOf(asteroidId)
        response        <- Ok(asteroidDetails)
      } yield response
  }

  // post /passthrough/favourites/{ Favourite }
  private val createFavourite: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "favourites" =>
      for {
        favourite <- req.as[Favourite]
        exists    <- favourites.exists(favourite.id)
        resp <-
          if exists
          then Conflict()
          else favourites.create(favourite).flatMap(Created(_))
      } yield resp
  }

  // get /passthrough/favourites
  private val getAllFavourites: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "favourites" =>
      favourites.all.flatMap(favourites => Ok(favourites))
  }

  val routes: HttpRoutes[F] = Router(
    prefix -> (searchByRange <+> detailsOf <+> createFavourite <+> getAllFavourites)
  )
}

object PassthroughController {
  def resource[F[_]: Async](
      client: AsteroidClient[F],
      favourites: Favourites[F]
  ): Resource[F, PassthroughController[F]] =
    Resource.pure(new PassthroughController[F](client, favourites))
}
