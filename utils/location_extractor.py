import re

# Lista de países comunes — puedes expandirla según tus necesidades
PAISES = [
    "Guatemala", "México", "Colombia", "Argentina", "Perú", "Chile", "España",
    "Estados Unidos", "Canadá", "Alemania", "Francia", "Italia", "Brasil",
    "Reino Unido", "India", "Australia", "Panamá", "Ecuador"
]

def detectar_pais_desde_texto(texto):
    for pais in PAISES:
        if re.search(rf'\b{pais}\b', texto, re.IGNORECASE):
            return pais
    return "Desconocido"
