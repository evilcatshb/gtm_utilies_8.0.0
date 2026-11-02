@echo off
setlocal
cd /d "%~dp0"
set "REMOTE=deepspace"
set "REPO_URL=https://github.com/evilcatshb/gtm_utilies_8.0.0.git"
set "BRANCH=deepspace-gtmutils-8.0.0"

echo === Sky of Grind DeepSpace GT Utilities fork sync ===
where git >nul 2>&1
if errorlevel 1 (echo ERROR: Git is not installed or not on PATH.& exit /b 1)
if not exist ".git" (echo ERROR: This directory is not a Git repository.& exit /b 1)
git remote get-url %REMOTE% >nul 2>&1
if errorlevel 1 git remote add %REMOTE% %REPO_URL%
if errorlevel 1 exit /b 1
git switch %BRANCH% >nul 2>&1
if errorlevel 1 git switch -c %BRANCH%
if errorlevel 1 exit /b 1

echo.
echo Staging source changes...
git add -A
if errorlevel 1 exit /b 1
git diff --cached --quiet
if errorlevel 1 (
  git commit -m "Sync Sky of Grind DeepSpace GT Utilities fork"
  if errorlevel 1 exit /b 1
) else (echo No new local changes to commit.)

echo.
echo Pushing %BRANCH% to GitHub...
git push -u %REMOTE% %BRANCH%
if errorlevel 1 (echo ERROR: Push failed. Check authentication or remote status.& exit /b 1)
echo.
echo Sync complete: https://github.com/evilcatshb/gtm_utilies_8.0.0
echo.
exit /b 0
