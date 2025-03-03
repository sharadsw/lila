package controllers

import play.api.data._
import play.api.data.Forms._
import scala.concurrent.duration._
import scala.util.chaining._

import lila.api.Context
import lila.api.GameApiV2
import lila.app._
import lila.common.config
import lila.db.dsl._
import lila.rating.PerfType
import lila.user.Holder

final class GameMod(env: Env)(implicit mat: akka.stream.Materializer) extends LilaController(env) {

  import GameMod._

  def index(username: String) =
    SecureBody(_.GamesModView) { implicit ctx => me =>
      OptionFuResult(env.user.repo named username) { user =>
        implicit def req = ctx.body
        val form         = filterForm.bindFromRequest()
        val filter       = form.fold(_ => emptyFilter, identity)
        env.tournament.leaderboardApi.recentByUser(user, 1) zip
          env.activity.read.recentSwissRanks(user.id) zip
          fetchGames(user, filter) flatMap { case ((arenas, swisses), povs) =>
            {
              if (isGranted(_.UserEvaluate))
                env.mod.assessApi.makeAndGetFullOrBasicsFor(povs) map Right.apply
              else fuccess(Left(povs))
            } map { games =>
              Ok(views.html.mod.games(user, form, games, arenas.currentPageResults, swisses))
            }
          }
      }
    }

  private def fetchGames(user: lila.user.User, filter: Filter) = {
    val select = toDbSelect(filter) ++ lila.game.Query.finished
    import akka.stream.scaladsl._
    env.game.gameRepo
      .recentGamesByUserFromSecondaryCursor(user, select)
      .documentSource(10_000)
      .filter { game =>
        filter.perf.fold(true)(game.perfKey ==)
      }
      .take(filter.nbGames)
      .mapConcat { lila.game.Pov(_, user).toList }
      .toMat(Sink.seq)(Keep.right)
      .run()
      .map(_.toList)

  }

  def post(username: String) =
    SecureBody(_.GamesModView) { implicit ctx => me =>
      OptionFuResult(env.user.repo named username) { user =>
        implicit val body = ctx.body
        actionForm
          .bindFromRequest()
          .fold(
            err => BadRequest(err.toString).fuccess,
            {
              case (gameIds, Some("pgn")) => downloadPgn(user, gameIds).fuccess
              case (gameIds, Some("analyse") | None) if isGranted(_.UserEvaluate) =>
                multipleAnalysis(me, gameIds)
              case _ => notFound
            }
          )
      }
    }

  private def multipleAnalysis(me: Holder, gameIds: Seq[lila.game.Game.ID])(implicit ctx: Context) =
    env.game.gameRepo.unanalysedGames(gameIds).flatMap { games =>
      games.map { game =>
        env.fishnet
          .analyser(
            game,
            lila.fishnet.Work.Sender(
              userId = me.id,
              ip = ctx.ip.some,
              mod = true,
              system = false
            )
          )
          .void
      }.sequenceFu >> env.fishnet.awaiter(games.map(_.id), 2 minutes)
    } inject NoContent

  private def downloadPgn(user: lila.user.User, gameIds: Seq[lila.game.Game.ID]) =
    Ok.chunked {
      env.api.gameApiV2.exportByIds(
        GameApiV2.ByIdsConfig(
          ids = gameIds,
          format = GameApiV2.Format.PGN,
          flags = lila.game.PgnDump.WithFlags(),
          perSecond = config.MaxPerSecond(100),
          playerFile = none
        )
      )
    }.pipe(asAttachmentStream(s"lichess_mod_${user.username}_${gameIds.size}_games.pgn"))
      .as(pgnContentType)

  private def guessSwisses(user: lila.user.User): Fu[Seq[lila.swiss.Swiss]] = fuccess(Nil)
}

object GameMod {

  case class Filter(
      arena: Option[String],
      swiss: Option[String],
      perf: Option[String],
      opponents: Option[String],
      nbGamesOpt: Option[Int]
  ) {
    def opponentIds: List[lila.user.User.ID] =
      (~opponents)
        .take(800)
        .replace(",", " ")
        .split(' ')
        .view
        .flatMap(_.trim.some.filter(_.nonEmpty))
        .filter(lila.user.User.couldBeUsername)
        .map(lila.user.User.normalize)
        .toList
        .distinct

    def nbGames = nbGamesOpt | 100
  }

  val emptyFilter = Filter(none, none, none, none, none)

  def toDbSelect(filter: Filter): Bdoc =
    lila.game.Query.notSimul ++
      filter.perf.?? { perf =>
        lila.game.Query.clock(perf != PerfType.Correspondence.key)
      } ++ filter.arena.?? { id =>
        $doc(lila.game.Game.BSONFields.tournamentId -> id)
      } ++ filter.swiss.?? { id =>
        $doc(lila.game.Game.BSONFields.swissId -> id)
      } ++ (filter.opponentIds match {
        case Nil      => $empty
        case List(id) => $and(lila.game.Game.BSONFields.playerUids $eq id)
        case ids      => $and(lila.game.Game.BSONFields.playerUids $in ids)
      })

  val filterForm =
    Form(
      mapping(
        "arena"      -> optional(nonEmptyText),
        "swiss"      -> optional(nonEmptyText),
        "speed"      -> optional(nonEmptyText),
        "opponents"  -> optional(nonEmptyText),
        "nbGamesOpt" -> optional(number(min = 1, max = 500))
      )(Filter.apply)(Filter.unapply _)
    )

  val actionForm =
    Form(
      tuple(
        "game"   -> list(nonEmptyText),
        "action" -> optional(lila.common.Form.stringIn(Set("pgn", "analyse")))
      )
    )
}
