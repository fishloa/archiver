.PHONY: dev-backend dev-scraper dev-frontend test lint validate-deploy

# Backend
dev-backend:
	cd backend && ./gradlew bootRun

test-backend:
	cd backend && ./gradlew test

lint-backend:
	cd backend && ./gradlew spotlessCheck

# Scraper
dev-scraper:
	cd scraper-cz && python -m scraper_cz.main

test-scraper:
	cd scraper-cz && pytest -v

lint-scraper:
	ruff check scraper-cz/ && ruff format --check scraper-cz/

# Bundesarchiv Scraper
test-barch:
	cd scraper-barch && pytest -v

lint-barch:
	ruff check scraper-barch/ && ruff format --check scraper-barch/

# OCR Worker
test-ocr:

lint-ocr:

# PDF Worker
test-pdf:
	cd pdf-worker && pytest -v

lint-pdf:
	ruff check pdf-worker/ && ruff format --check pdf-worker/

# Entity Worker
test-entity:
	cd entity-worker && pytest -v

lint-entity:
	ruff check entity-worker/ && ruff format --check entity-worker/

# Frontend
dev-frontend:
	cd frontend && bun run dev

test-frontend:
	cd frontend && bun test

lint-frontend:
	cd frontend && npx eslint . && npx prettier --check .

# Smoke test (live site)
test-smoke:
	web/test-endpoints.sh

# Full deployment validation (live site)
validate-deploy:
	web/validate-deploy.sh

# All
test: test-backend test-scraper test-ocr test-pdf test-entity test-frontend
lint: lint-backend lint-scraper lint-ocr lint-pdf lint-entity lint-frontend
