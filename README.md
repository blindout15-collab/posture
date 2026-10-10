# 자세 지킴이

웹캠이나 휴대폰 전면 카메라로 얼굴을 추적해서 **화면과 너무 가까워지거나**, **고개를 숙이거나 기울이면** 알려주는 웹앱입니다.
얼굴 인식은 [MediaPipe Face Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker)로 브라우저 안에서 처리하고, 영상은 어디에도 전송되지 않습니다.

## 기능
- 화면 거리 추정: 기준 자세를 저장할 때의 얼굴 크기와 비교해서 cm 단위로 추정
- 고개 숙임과 젖힘(pitch), 좌우 기울기(roll) 감지
- 나쁜 자세가 몇 초 이어지면 화면 배너, 소리, 진동, 시스템 알림으로 알려줌
- 20-20-20 눈 휴식 알림 (20분마다)
- 오늘 측정 시간, 바른 자세 비율, 알림 횟수 기록 (브라우저에만 저장)

## 사용법
1. ‘시작’을 누르고 카메라 권한을 허용합니다.
2. 바른 자세로 앉아서 ‘기준 자세 저장’을 누릅니다. (설정에 실제 화면 거리를 적으면 더 정확해요.)
3. PC에서는 탭을 열어둔 채 다른 작업을 하면 됩니다. 휴대폰은 앱 화면이 켜져 있을 때만 동작합니다.

## 실행
카메라는 HTTPS나 localhost에서만 켜집니다.

```bash
python3 -m http.server 8000
# http://localhost:8000 접속
```

### GitHub Pages 배포
`main`에 푸시하면 `.github/workflows/pages.yml`이 배포합니다. 처음 한 번은 저장소의 **Settings → Pages → Source**를 **GitHub Actions**로 바꿔야 합니다.
