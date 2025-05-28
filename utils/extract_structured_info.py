import os
from openai import OpenAI
from dotenv import load_dotenv

load_dotenv()
client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

def extract_structured_info(cv_text: str):
    prompt = f"""
You are a helpful AI that extracts structured information from a resume.
The resume content is below (in English or Spanish). Return a JSON with the following fields:
- nombre, email, telefono, linkedin, ubicacion (pais, ciudad),
- educacion (titulo, institucion, estado, años),
- experiencia (empresa, cargo, duracion, ubicacion, logros),
- tecnologias, certificaciones, idiomas (idioma, nivel),
- habilidades_blandas, reubicacion_disponible, preferencia_trabajo, disponibilidad, objetivo_profesional.

Resume content:
\"\"\"{cv_text}\"\"\"
"""

    response = client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=[
            {"role": "user", "content": prompt}
        ],
        temperature=0.2
    )

    return response.choices[0].message.content