import com.codingame.gameengine.runner.SoloGameRunner

fun main(args: Array<String>) {
    val gameRunner = SoloGameRunner()
    // gameRunner.setAgent(Agent::class.java)
    //gameRunner.setAgent("Python \"G:\\Alterpath\\maps\\World 1\\Bot\\bot.py\"")
    gameRunner.setAgent("py src/test/bot.py")
    gameRunner.setTestCaseInput("0\n0\n0\n0\n0")
    gameRunner.start()
}