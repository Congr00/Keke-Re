package com.codingame.game

import com.codingame.game.engine.Engine
import com.codingame.gameengine.core.AbstractPlayer.TimeoutException
import com.codingame.gameengine.core.AbstractReferee
import com.codingame.gameengine.core.SoloGameManager
import com.codingame.gameengine.module.entities.Curve
import com.codingame.gameengine.module.entities.GraphicEntityModule
import com.codingame.gameengine.module.entities.Rectangle
import com.codingame.gameengine.module.entities.Sprite
import com.google.inject.Inject

class Referee : AbstractReferee() {
    @Inject
    private lateinit var gameManager: SoloGameManager<Player>

    @Inject
    private lateinit var graphicEntityModule: GraphicEntityModule

    private lateinit var map: Array<Rectangle>
    private lateinit var player: Sprite
    private var playerCoord: Array<Int> = arrayOf(0, 0)
    private lateinit var engine: Engine

    override fun init() {
        // Initialize your game here.
        gameManager.maxTurns = 5
        // val width = 10
        // map = Array(width * width) {
        //     val x = it % width
        //     val y = it / width

        //     graphicEntityModule.createRectangle().apply {
        //         this.height = 50
        //         this.width = 50
        //         this.x = x * this.height + x
        //         this.y = y * this.width + y
        //         this.fillColor = 0xc00fee
        //     }
        // }
        // player = graphicEntityModule.createSprite().apply {
        //     this.image = "keke.png"
        // }
        // updatePlayer()

        engine = Engine(graphicEntityModule)
    }

    private fun updatePlayer() {
        val (x, y) = playerCoord
        player.setX(25 + x * 50 + x, Curve.NONE)
        player.setY(25 + y * 50 + y, Curve.NONE)
    }

    override fun gameTurn(turn: Int) {
        // playerCoord[0] += 1
        // playerCoord[1] += 1
        // updatePlayer()

        val player = gameManager.player
        player.sendInputLine("input")
        player.execute()

        try {
            val outputs = player.outputs
            engine.update(outputs[0])
        } catch (e: TimeoutException) {
            // kekere?
            System.err.println(e)
        }
    }
}