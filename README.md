
# 🧠 JobSeeker AI — Intelligent Job Search System

JobSeeker AI is a personal AI-powered job search assistant built with Python and FastAPI. It automatically analyzes your uploaded CV (`.pdf` or `.docx`), detects your country of residence, and performs real-time scraping of global job offers. It prioritizes listings based on your location and relocation preferences. Additionally, it auto-generates an optimized, ATS-compliant CV tailored to each job offer.

## 🚀 Features

### 📄 Phase 1: CV Processing
- Upload `.pdf` or `.docx` resume files.
- Extract text using `pdfplumber` and `docx2txt`.
- Automatically detect the language of the CV (English or Spanish).
- Identify the user's country of residence by scanning for country names.
- Clean and preprocess resume data for downstream use.

### 🌍 Phase 2: Real-Time Job Scraping
- Integrates real-time job scraping from sites such as:
  - LinkedIn
  - Indeed
  - Glassdoor
  - RemoteOK
  - Stack Overflow Jobs (if available)
- Prioritizes job listings in the following order:
  1. On-site or remote jobs in the user’s country.
  2. Fully remote global jobs.
  3. International jobs that support relocation.

### 🤖 Phase 3: AI-Based CV Optimization (in progress)
- Automatically rewrites and formats a new CV tailored for each job found.
- Uses NLP techniques and OpenAI models to:
  - Highlight relevant skills and experiences.
  - Reorganize sections for better ATS readability.
  - Match terminology used in job descriptions.
- Ensures HR-friendly design and formatting.

## 🛠️ Tech Stack

| Layer        | Technology            |
|--------------|------------------------|
| Backend API  | FastAPI                |
| NLP/Parsing  | `pdfplumber`, `docx2txt`, `langdetect`, `re` |
| Scraping     | `requests`, `BeautifulSoup`, `Selenium` (planned) |
| AI Models    | OpenAI GPT API, spaCy |
| Environment  | Python 3.11+, `venv` |
| Hosting (optional) | Docker, Railway/Render/Oracle Cloud |

## 🔁 API Endpoints

### `POST /cargar-cv/`
Upload a resume in `.pdf` or `.docx` format.

**Response**
```json
{
  "pais_detectado": "Guatemala",
  "resumen_cv": "Summary of parsed resume text..."
}
```

### `GET /buscar-empleos`
Searches for job offers based on your detected country and language preferences.

**Planned response**
```json
[
  {
    "title": "Full Stack .NET Developer",
    "location": "Remote - Guatemala",
    "url": "https://www.linkedin.com/jobs/view/...",
    "relocation": false
  },
  ...
]
```

### `POST /optimizar-cv`
(Coming soon) Generates an optimized version of the CV tailored to a specific job offer.

## 📁 Project Structure

```
JobSeeker/
│
├── main.py                 # FastAPI entrypoint
├── requirements.txt        # Dependencies
├── extract_text.py         # CV text extraction & country detection
├── scraping/
│   └── linkedin_scraper.py # Real job site scrapers (WIP)
├── ai/
│   └── cv_optimizer.py     # CV rewriting logic (planned)
├── tests/
│   └── test_cv_parsing.py  # Unit tests
└── .gitignore              # Ignored files
```

## 📦 Installation

```bash
git clone https://github.com/yourusername/JobSeeker.git
cd JobSeeker
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```

## 🔒 Security and Ethics

- Your CV is never stored permanently.
- All processing is done locally or via secure, authenticated APIs.
- The project is intended for personal use and educational purposes.

## 📌 Roadmap

- PDF and DOCX parsing
- Country detection from CV
- Job prioritization logic
- Real-time scraping from multiple sources
- AI-powered CV generation
- Web interface with drag & drop CV upload
- Multi-language support (EN/ES/DE)

## 👨‍💻 About the Developer

**Eder Yafeth García Quiroa**  
🇬🇹 Software Developer | .NET Backend | AI & Automation Enthusiast  
🔗 [LinkedIn](https://www.linkedin.com/in/eder-garcia-q/) <!--| 🌐 [Portfolio](https://yourportfolio.com)-->

## 📝 License

MIT License