package com.blindout.postureguard

/** 한 프레임에서 잰 얼굴 값. width는 얼굴 폭 / 이미지 짧은 변 (0~1). 각도는 ML Kit 기준 도(°). */
data class Sample(val width: Float, val pitch: Float, val roll: Float)

data class Thresholds(
    val baseDistCm: Int,
    val minDistCm: Int,
    val pitchTolDeg: Int,
    val rollTolDeg: Int,
)

data class Problem(val key: String, val message: String)

data class Evaluation(
    val distanceCm: Float,
    /** 기준 자세보다 고개를 숙인 각도. 양수 = 숙임, 음수 = 젖힘. */
    val pitchDown: Float,
    val roll: Float,
    val problems: List<Problem>,
)

object PostureEvaluator {

    fun evaluate(sample: Sample, baseline: Sample, t: Thresholds): Evaluation {
        val distance = t.baseDistCm * baseline.width / sample.width
        // ML Kit의 headEulerAngleX는 위를 볼수록 커진다.
        val pitchDown = angleDiff(baseline.pitch, sample.pitch)
        val roll = angleDiff(sample.roll, baseline.roll)

        val problems = mutableListOf<Problem>()
        if (distance < t.minDistCm) {
            problems += Problem("close", "화면과 너무 가까워요 (약 ${distance.toInt()}cm). 조금 뒤로 물러나 주세요.")
        }
        if (pitchDown > t.pitchTolDeg) {
            problems += Problem("down", "고개를 너무 숙이고 있어요. 턱을 당기고 화면을 눈높이로 올려주세요.")
        } else if (pitchDown < -t.pitchTolDeg * 1.5f) {
            problems += Problem("up", "고개가 너무 뒤로 젖혀져 있어요. 화면 높이를 확인해 주세요.")
        }
        if (kotlin.math.abs(roll) > t.rollTolDeg) {
            problems += Problem("tilt", "고개가 한쪽으로 기울어 있어요. 똑바로 앉아주세요.")
        }
        return Evaluation(distance, pitchDown, roll, problems)
    }

    fun average(samples: List<Sample>): Sample = Sample(
        width = samples.map { it.width }.average().toFloat(),
        pitch = samples.map { it.pitch }.average().toFloat(),
        roll = samples.map { it.roll }.average().toFloat(),
    )

    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }
}
