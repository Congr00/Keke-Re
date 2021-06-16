import com.codingame.gameengine.runner.SoloGameRunner

fun main(args: Array<String>) {
    val gameRunner = SoloGameRunner()
    // gameRunner.setAgent(Agent::class.java)
    //gameRunner.setAgent("Python \"G:\\Alterpath\\maps\\World 1\\Bot\\bot.py\"")
    gameRunner.setAgent("python src/test/bot.py")
    gameRunner.setTestCaseInput("world1/map6.tmx")
    gameRunner.start()
}