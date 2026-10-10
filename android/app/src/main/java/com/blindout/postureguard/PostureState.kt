package com.blindout.postureguard

import kotlinx.coroutines.flow.MutableStateFlow

enum class Level { IDLE, GOOD, WARN, BAD }

data class UiState(
    val running: Boolean = false,
    val level: Level = Level.IDLE,
    val label: String = "대기 중",
    val detail: String = "‘시작’을 누른 다음 바른 자세로 앉아서 ‘기준 자세 저장’을 눌러주세요.",
    val evaluation: Evaluation? = null,
)

/** 서비스가 쓰고 화면이 읽는 현재 상태. 같은 프로세스 안에서만 공유된다. */
object PostureState {
    val ui = MutableStateFlow(UiState())
}
