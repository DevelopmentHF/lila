package lila.swiss

import org.joda.time.DateTime
import chess.Clock.{ LimitMinutes, LimitSeconds, IncrementSeconds }

import lila.db.dsl.{ *, given }

final private class SwissOfficialSchedule(mongo: SwissMongo, cache: SwissCache)(using
    Executor
):
  import SwissOfficialSchedule.*

  private val classical   = Config("Classical", 30, IncrementSeconds(0), 5, 5)
  private val rapid       = Config("Rapid", 10, IncrementSeconds(0), 7, 8)
  private val blitz       = Config("Blitz", 5, IncrementSeconds(0), 10, 12)
  private val superblitz  = Config("SuperBlitz", 3, IncrementSeconds(0), 12, 12)
  private val bullet      = Config("Bullet", 1, IncrementSeconds(0), 15, 15)
  private val hyperbullet = Config("HyperBullet", 0.5, IncrementSeconds(0), 20, 15)

  // length must divide 24 (schedule starts at 0AM)
  // so either 3, 4, 6, 8, 12
  private val schedule    = Vector(classical, bullet, rapid, hyperbullet, blitz, superblitz)
  private def daySchedule = (0 to 23).toList.flatMap(i => schedule.lift(i % schedule.length))

  def generate: Funit =
    val dayStart = DateTime.now.plusDays(3).withTimeAtStartOfDay
    daySchedule.zipWithIndex
      .map { case (config, hour) =>
        val startAt = dayStart plusHours hour
        mongo.swiss.exists($doc("teamId" -> lichessTeamId, "startsAt" -> startAt)) flatMap {
          case true => fuFalse
          case _ => mongo.swiss.insert.one(BsonHandlers.addFeaturable(makeSwiss(config, startAt))) inject true
        }
      }
      .parallel
      .map { res =>
        if (res.exists(identity)) cache.featuredInTeam.invalidate(lichessTeamId)
      }

  private def makeSwiss(config: Config, startAt: DateTime) =
    Swiss(
      _id = Swiss.makeId,
      name = config.name,
      clock = config.clock,
      variant = chess.variant.Standard,
      round = SwissRoundNumber(0),
      nbPlayers = 0,
      nbOngoing = 0,
      createdAt = DateTime.now,
      createdBy = lila.user.User.lichessId,
      teamId = lichessTeamId,
      nextRoundAt = startAt.some,
      startsAt = startAt,
      finishedAt = none,
      winnerId = none,
      settings = Swiss.Settings(
        nbRounds = config.nbRounds,
        rated = true,
        description = none,
        position = none,
        roundInterval = SwissForm.autoInterval(config.clock),
        password = none,
        conditions = SwissCondition
          .All(nbRatedGame = SwissCondition.NbRatedGame(config.minGames).some, none, none, none, none),
        forbiddenPairings = "",
        manualPairings = ""
      )
    )

private object SwissOfficialSchedule:
  case class Config(
      name: String,
      clockMinutes: Double,
      clockSeconds: IncrementSeconds,
      nbRounds: Int,
      minGames: Int
  ):
    def clock = chess.Clock.Config(LimitSeconds((clockMinutes * 60).toInt), clockSeconds)
