# clien-shredder

클리앙 댓글 삭제기

## Requirements

[Leiningen](https://leiningen.org)이 필요합니다.

## How to run

1. `core.clj`을 열어 `user-id`, `user-pw`을 자신의 계정에 맞게 수정합니다.

2. Terminal에서 Project root로 이동 후 `lein repl`을 입력하여 REPL을 실행합니다.

3. (주의) ```clien-shredder.core=> (delete-comments)``` 을 입력하면 댓글 삭제 작업을 시작합니다.