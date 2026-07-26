# Releasing

All artifacts use Maven group `games.cafecito.foundry`. Publish only from a clean, verified commit:

```sh
./gradlew clean check
./gradlew --write-locks
git diff --check
```

Review dependency-lock changes and generated Maven metadata before publication. Archive timestamps
and order are normalized by the shared build convention for reproducible outputs.
