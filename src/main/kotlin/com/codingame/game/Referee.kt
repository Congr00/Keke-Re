package com.codingame.game

import com.codingame.game.engine.Engine
import com.codingame.gameengine.core.AbstractPlayer.TimeoutException
import com.codingame.gameengine.core.AbstractReferee
import com.codingame.gameengine.core.SoloGameManager
import com.codingame.gameengine.module.entities.GraphicEntityModule
import com.google.inject.Inject
import com.codingame.gameengine.module.entities.World as GameEngineWorld

class Referee : AbstractReferee() {
    @Inject
    private lateinit var gameManager: SoloGameManager<Player>

    @Inject
    private lateinit var graphicEntityModule: GraphicEntityModule

    @Inject
    private lateinit var worldModule: GameEngineWorld

    private lateinit var engine: Engine

    override fun init() {
        // Initialize your game here.
        gameManager.maxTurns = 1000
        gameManager.firstTurnMaxTime = 75
        gameManager.turnMaxTime = 75

        val mapPath = gameManager.testCaseInput[0]
        engine = Engine(mapPath, graphicEntityModule, worldModule)

        val (width, height) = engine.mapSize
        gameManager.player.sendInputLine("$width $height ${gameManager.maxTurns}")
    }

    override fun gameTurn(turn: Int) {
        val player = gameManager.player
        val positionData = engine.getVisibleEntities()

        // Send number of visible blocks & player position
        val (px, py) = engine.playerPosition
        player.sendInputLine("${positionData.size} $px $py")

        // For each position, send...
        for ((position, entityList) in positionData) {
            // ...position and number of entities...
            player.sendInputLine("${position.x} ${position.y} ${entityList.size}")
            // ...and entity description for each entity
            for (entityDescription in entityList) {
                player.sendInputLine(entityDescription)
            }
        }

        player.execute()

        try {
            val outputs = player.outputs
            engine.update(outputs[0])
        } catch (e: TimeoutException) {
            System.err.println("Timeout")
            gameManager.loseGame("Timeout")
        }

        if (engine.gameWon()) {
            println("Congrats, KEKE wins!")
            gameManager.winGame()
        }

        if (engine.playerDied()) {
            println("KEKE died!")
            engine.reset()
        }

        engine.updateVision()
    }
}