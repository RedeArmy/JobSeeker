from fastapi import FastAPI, File, UploadFile
import os
from utils.resumen_parser import extract_text
from utils.lang_detector import detectar_idioma
from utils.skill_extractor import extraer_skills
from utils.location_extractor import detectar_pais_desde_texto

UPLOAD_FOLDER = "uploads"
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

app = FastAPI()

@app.post("/upload-cv")
async def upload_cv(file: UploadFile = File(...)):
    filepath = os.path.join(UPLOAD_FOLDER, file.filename)
    with open(filepath, "wb") as f:
        f.write(await file.read())

    try:
        text = extract_text(filepath)
        language = detectar_idioma(text)
        habilidades = extraer_skills(text, language)
        residencia = detectar_pais_desde_texto(text)

        return {
            "idioma_detectado": language,
            "habilidades_detectadas": habilidades,
            "pais_residencia": residencia,
            "fragmento_cv": text[:500]
        }
    except Exception as e:
        return {"error": str(e)}