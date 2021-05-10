package com.codingame.game

import com.codingame.gameengine.core.AbstractPlayer.TimeoutException
import com.codingame.gameengine.core.AbstractReferee
import com.codingame.gameengine.core.MultiplayerGameManager
import com.codingame.gameengine.module.entities.*
import com.google.inject.Inject

class Referee : AbstractReferee() {
    @Inject
    private lateinit var gameManager: MultiplayerGameManager<Player>
    @Inject
    private lateinit var graphicEntityModule: GraphicEntityModule

    private lateinit var map: Array<Rectangle>
    private lateinit var player: Circle
    private var playerCoord: Array<Int> = arrayOf(0, 0)

    override fun init() {
        // Initialize your game here.
        gameManager.maxTurns = 5
        val width = 10
        map = Array(width * width) {
            val x = it % width
            val y = it / width

            graphicEntityModule.createRectangle().apply {
                this.height = 50
                this.width = 50
                this.x = x * this.height + x
                this.y = y * this.width + y
                this.fillColor = 0xc00fee
            }
        }
        player = graphicEntityModule.createCircle().apply {
            this.fillColor = 0x0000ff
            this.radius = 25
        }
        updatePlayer()
    }

    private fun updatePlayer() {
        val (x, y) = playerCoord
        player.setX(25 + x * 50 + x, Curve.NONE)
        player.setY(25 + y * 50 + y, Curve.NONE)
    }

    override fun gameTurn(turn: Int) {
        for (player: Player in gameManager.activePlayers) {
            player.sendInputLine("input")
            player.execute()
        }

        for (player: Player in gameManager.activePlayers) {
            try {
                val outputs = player.outputs
            } catch (e: TimeoutException) {
                // kekere?
            }
        }
    }
}