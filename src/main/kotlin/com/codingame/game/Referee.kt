package com.codingame.game

import com.codingame.game.engine.Engine
import com.codingame.gameengine.core.AbstractPlayer.TimeoutException
import com.codingame.gameengine.core.AbstractReferee
import com.codingame.gameengine.core.SoloGameManager
import com.codingame.gameengine.module.entities.GraphicEntityModule
import com.codingame.gameengine.module.tooltip.TooltipModule
import com.google.inject.Inject
import com.codingame.gameengine.module.entities.World as GameEngineWorld

class Referee : AbstractReferee() {
    @Inject
    private lateinit var gameManager: SoloGameManager<Player>

    @Inject
    private lateinit var graphicEntityModule: GraphicEntityModule

    @Inject
    private lateinit var worldModule: GameEngineWorld

    @Inject
    private lateinit var tooltipsModule: TooltipModule

    private lateinit var engine: Engine

    override fun init() {
        // Initialize your game here.
        val turnLimit = gameManager.testCaseInput[1].toInt()
        gameManager.maxTurns = turnLimit
        gameManager.firstTurnMaxTime = 100
        gameManager.turnMaxTime = 100

        val mapPath = gameManager.testCaseInput[0]
        engine = Engine(mapPath, graphicEntityModule, worldModule, tooltipsModule)

        val (width, height) = engine.mapSize
        gameManager.player.sendInputLine("$width $height ${gameManager.maxTurns}")
    }

    override fun gameTurn(turn: Int) {
        val player = gameManager.player
        val positionData = engine.getVisibleEntities()

        // Send number of visible blocks & player position
        val (px, py) = engine.playerPosition
        val numOfEntities = positionData.sumOf { it.second.size }
        player.sendInputLine("$numOfEntities $px $py")

        // For each entity send its position and attributes
        val entitiesDescription = positionData.asSequence()
            .flatMap { (position, entityList) -> entityList.map { "${position.x} ${position.y} $it" } }
        for (entityDescription in entitiesDescription) {
            player.sendInputLine(entityDescription)
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
        engine.updateTooltips()
    }
}