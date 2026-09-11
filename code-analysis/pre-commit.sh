#!/bin/bash

echo "Running pre-commit hook"
echo
echo "NOTE: The pre-commit hook runs on all files in the working tree, not just on staged changes.
If this causes the hook to fail, you can stash your unstaged changes before committing again."
echo

./gradlew spotlessCheck checkstyleMain pmdMain test

if [ $? -ne 0 ]
then
  echo "Pre-commit hook failed"
  exit 1
fi

echo "Pre-commit hook passed"
exit 0
