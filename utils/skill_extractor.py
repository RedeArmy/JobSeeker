import spacy

# Cargar modelos multilingües
nlp_en = spacy.load("en_core_web_sm")
nlp_es = spacy.load("es_core_news_sm")

# Lista simple de tecnologías y herramientas conocidas
SKILLS = [
    ".NET", "C#", "Azure", "AWS", "JavaScript", "React", "SQL", "PostgreSQL",
    "MongoDB", "Python", "Microservices", "Docker", "Kubernetes", "CI/CD",
    "Git", "Jenkins", "ASP.NET", "Entity Framework"
]

def extraer_skills(texto, idioma):
    nlp = nlp_en if idioma.startswith("en") else nlp_es
    doc = nlp(texto)
    texto_limpio = " ".join([token.text for token in doc])
    return [s for s in SKILLS if s.lower() in texto_limpio.lower()]
