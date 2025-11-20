@echo off
python -m venv venv
call venv\Scripts\activate
pip install --upgrade pip setuptools wheel
pip install -r requirements-lite.txt
uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
