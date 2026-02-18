package io.github.mee1080.umasim.race

import io.github.mee1080.umasim.race.calc2.RaceSetting
import io.github.mee1080.umasim.race.calc2.RaceCalculator
import io.github.mee1080.umasim.race.calc2.SystemSetting
import io.github.mee1080.umasim.race.data2.SkillData
import io.github.mee1080.umasim.race.data2.skillData2
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class CliInput(
    val baseSetting: RaceSetting,
    val acquiredSkillIds: List<Int>,
    val unacquiredSkillIds: List<Int>,
    val iterations: Int = 100
)

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("{\"error\": \"No JSON payload provided\"}")
        return
    }

    val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
    }

    try {
        val payload = jsonParser.decodeFromString<CliInput>(args[0])
        
        val results = calculateSkillDifferentials(
            payload.baseSetting,
            payload.acquiredSkillIds,
            payload.unacquiredSkillIds,
            payload.iterations
        )
        
        println(jsonParser.encodeToString(results))
        
    } catch (e: Exception) {
        System.err.println("{\"error\": \"Simulation failed: ${e.message}\"}")
    }
}

fun calculateSkillDifferentials(
    baseSetting: RaceSetting, 
    acquiredSkillIds: List<Int>, 
    unacquiredSkillIds: List<Int>, 
    iterations: Int
): Map<String, Double> {
    val calculator = RaceCalculator(SystemSetting())
    
    // Convert all incoming Python integers into Strings to guarantee safe comparison
    val acquiredStr = acquiredSkillIds.map { it.toString() }
    val unacquiredStr = unacquiredSkillIds.map { it.toString() }
    
    // 1. Build the absolute baseline using ONLY acquired skills
    val baseSkills = skillData2.filter { skill: SkillData -> 
        acquiredStr.contains(skill.id.toString()) 
    }
    
    val baselineSetting = baseSetting.copy(
        umaStatus = baseSetting.umaStatus.copy(hasSkills = baseSkills)
    )
    
    // 2. Run the baseline simulations
    val baseTimes = mutableListOf<Double>()
    for (i in 0 until iterations) {
        val simResult = calculator.simulate(baselineSetting)
        baseTimes.add(simResult.first.raceTime.toDouble())
    }
    val avgBaseTime = baseTimes.average()
    
    val differentials = mutableMapOf<String, Double>()
    
    // 3. Test unacquired skills one by one on top of the baseline
    val targetSkills = skillData2.filter { skill: SkillData -> 
        unacquiredStr.contains(skill.id.toString()) 
    }
    
    for (targetSkill in targetSkills) {
        val testSetting = baselineSetting.copy(
            umaStatus = baselineSetting.umaStatus.copy(hasSkills = baseSkills + targetSkill)
        )
        
        val testTimes = mutableListOf<Double>()
        for (i in 0 until iterations) {
            val simResult = calculator.simulate(testSetting)
            testTimes.add(simResult.first.raceTime.toDouble())
        }
        val avgTestTime = testTimes.average()
        
        // A positive number means the skill made the race faster
        differentials[targetSkill.name] = avgBaseTime - avgTestTime
    }
    
    return differentials
}