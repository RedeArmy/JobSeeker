"""Conjunto piloto SINTÉTICO para comparar modelos de embeddings (no sustituye el protocolo de 300 pares etiquetados).

Verdad-terreno: relevancia 0-3 calculada a partir de la familia profesional y del solapamiento de habilidades.
El modelo debe recuperarla SOLO del texto (sin ver las etiquetas)."""
import json, random

R = random.Random(7)

# familia -> (títulos ES, títulos EN, habilidades, responsabilidades ES, responsabilidades EN, formación ES, formación EN)
F = {
 "backend": (["Desarrollador backend", "Ingeniero de software backend", "Programador Java"], ["Backend developer", "Software engineer (backend)"],
   ["Java", "Spring Boot", "PostgreSQL", "REST APIs", "Docker", "Hibernate", "Redis", "Microservicios", "JUnit", "Git", "Kafka", "SQL"],
   "diseño e implementación de APIs y servicios del lado del servidor, optimización de consultas y pruebas automatizadas",
   "design and implementation of server-side APIs and services, query optimization and automated testing",
   "Ingeniería en Ciencias y Sistemas", "B.Sc. in Computer Science"),
 "frontend": (["Desarrollador frontend", "Ingeniero de interfaces web", "Desarrollador web"], ["Frontend developer", "Web UI engineer"],
   ["JavaScript", "TypeScript", "React", "HTML", "CSS", "Redux", "Webpack", "Accesibilidad web", "Jest", "Figma", "Next.js", "Git"],
   "construcción de interfaces de usuario accesibles y responsivas, integración con APIs y optimización del rendimiento en el navegador",
   "building accessible, responsive user interfaces, API integration and browser performance tuning",
   "Ingeniería en Sistemas", "B.Sc. in Software Engineering"),
 "data": (["Ingeniero de datos", "Especialista en pipelines de datos", "Data engineer"], ["Data engineer", "Big data engineer"],
   ["Python", "Apache Spark", "Airflow", "SQL", "ETL", "Data warehouse", "Kafka", "AWS S3", "dbt", "Pandas", "Hadoop", "Docker"],
   "construcción y mantenimiento de pipelines de datos, modelado de data warehouse y aseguramiento de la calidad de los datos",
   "building and maintaining data pipelines, data warehouse modeling and data quality assurance",
   "Ingeniería en Ciencias y Sistemas", "B.Sc. in Computer Science"),
 "devops": (["Ingeniero DevOps", "Administrador de infraestructura cloud", "Ingeniero de plataforma"], ["DevOps engineer", "Cloud infrastructure engineer"],
   ["Kubernetes", "Docker", "Terraform", "AWS", "CI/CD", "Linux", "Prometheus", "Grafana", "Ansible", "GitHub Actions", "Bash", "Helm"],
   "automatización de despliegues, gestión de infraestructura como código y monitoreo de servicios en producción",
   "deployment automation, infrastructure-as-code management and production service monitoring",
   "Ingeniería en Sistemas", "B.Sc. in Information Systems"),
 "qa": (["Ingeniero de QA automatizado", "Analista de pruebas de software", "Tester automatizador"], ["QA automation engineer", "Software test analyst"],
   ["Selenium", "Cypress", "Playwright", "JUnit", "Pruebas de regresión", "Postman", "Jira", "BDD", "Cucumber", "SQL", "API testing", "CI/CD"],
   "diseño de planes de prueba, automatización de pruebas funcionales y de regresión y reporte de defectos",
   "test plan design, functional and regression test automation and defect reporting",
   "Ingeniería en Sistemas", "B.Sc. in Information Systems"),
 "contabilidad": (["Contador general", "Analista contable", "Auxiliar de contabilidad y finanzas"], ["Staff accountant", "Financial accountant"],
   ["NIIF", "Conciliaciones bancarias", "Excel avanzado", "SAP", "Impuestos (IVA e ISR)", "Estados financieros", "Nómina", "Auditoría", "Cuentas por pagar", "Presupuestos", "Flujo de caja", "SQL"],
   "registro de operaciones contables, cierre mensual, conciliaciones, declaración de impuestos y preparación de estados financieros",
   "bookkeeping, monthly close, reconciliations, tax filings and financial statement preparation",
   "Licenciatura en Contaduría Pública y Auditoría", "B.Sc. in Accounting"),
 "ventas": (["Ejecutivo de ventas", "Representante comercial B2B", "Gerente de cuentas"], ["Sales executive", "B2B account manager"],
   ["Prospección de clientes", "CRM (Salesforce)", "Negociación", "Cierre de ventas", "Presentaciones comerciales", "Pronóstico de ventas", "Cuentas clave", "Marketing digital", "LinkedIn Sales Navigator", "Excel", "Servicio al cliente", "Cotizaciones"],
   "prospección y cierre de clientes corporativos, seguimiento del pipeline comercial y cumplimiento de metas de ventas",
   "prospecting and closing corporate clients, sales pipeline follow-up and quota attainment",
   "Licenciatura en Administración de Empresas", "B.A. in Business Administration"),
 "rrhh": (["Analista de recursos humanos", "Reclutador y selección de personal", "Generalista de talento humano"], ["HR generalist", "Talent acquisition specialist"],
   ["Reclutamiento y selección", "Entrevistas por competencias", "Legislación laboral", "Nómina", "Onboarding", "Clima organizacional", "Evaluación del desempeño", "ATS", "Capacitación", "Excel", "Relaciones laborales", "Employer branding"],
   "atracción y selección de talento, gestión de contratación e inducción y administración de relaciones laborales",
   "talent attraction and selection, hiring management, onboarding and employee relations",
   "Licenciatura en Psicología Industrial", "B.A. in Industrial Psychology"),
}
ADJ = {("backend", "frontend"), ("backend", "devops"), ("backend", "data"), ("backend", "qa"), ("frontend", "qa"), ("devops", "data"),
       ("contabilidad", "ventas"), ("ventas", "rrhh"), ("contabilidad", "rrhh")}
adjacent = lambda a, b: (a, b) in ADJ or (b, a) in ADJ

SEN = {"junior": (1, "Junior", "Junior"), "semi": (4, "Semi senior", "Mid-level"), "senior": (8, "Senior", "Senior")}

def candidate(fid, lang, i):
    es_t, en_t, skills, resp_es, resp_en, edu_es, edu_en = F[fid]
    sen = R.choice(list(SEN)); yrs = SEN[sen][0] + R.randint(0, 2)
    k = R.randint(5, 11); own = R.sample(skills, k)
    foreign_fid = R.choice([x for x in F if x != fid]); foreign = R.sample(F[foreign_fid][2], R.randint(0, 2))  # trampas de palabras clave
    sk = own + [s for s in foreign if s not in own]; R.shuffle(sk)
    if lang == "es":
        title = R.choice(es_t)
        text = f"{title} {SEN[sen][1]}. {yrs} años de experiencia en {resp_es}. Habilidades: {', '.join(sk)}. Formación: {edu_es}."
    else:
        title = R.choice(en_t)
        text = f"{SEN[sen][2]} {title}. {yrs} years of experience in {resp_en}. Skills: {', '.join(sk)}. Education: {edu_en}."
    return {"id": f"c_{fid}_{i}", "family": fid, "lang": lang, "skills": own, "text": text, "title": title}

def job(fid, lang, i):
    es_t, en_t, skills, resp_es, resp_en, edu_es, edu_en = F[fid]
    req = R.sample(skills, R.randint(5, 7))
    if lang == "es":
        text = f"Se busca {R.choice(es_t)}. Responsabilidades: {resp_es}. Requisitos: {', '.join(req)}. Formación: {edu_es} o afín."
    else:
        text = f"We are hiring a {R.choice(en_t)}. Responsibilities: {resp_en}. Requirements: {', '.join(req)}. Education: {edu_en} or related."
    return {"id": f"j_{fid}_{i}", "family": fid, "lang": lang, "req": req, "text": text}

def relevance(j, c):
    if j["family"] == c["family"]:
        ov = len(set(j["req"]) & set(c["skills"])) / len(j["req"])
        return 3 if ov >= 0.7 else 2 if ov >= 0.4 else 1
    return 1 if adjacent(j["family"], c["family"]) else 0

cands, jobs = [], []
for fid in F:
    for i in range(10): cands.append(candidate(fid, "es" if i < 7 else "en", i))
    for i in range(3): jobs.append(job(fid, "es" if i % 2 == 0 else "en", i))
rel = {j["id"]: {c["id"]: relevance(j, c) for c in cands} for j in jobs}
json.dump({"candidates": cands, "jobs": jobs, "rel": rel}, open("dataset.json", "w"), ensure_ascii=False, indent=1)
from collections import Counter
cnt = Counter(v for d in rel.values() for v in d.values())
print("candidatos", len(cands), "vacantes", len(jobs), "pares", len(jobs) * len(cands), "distribución de relevancia", dict(sorted(cnt.items())))
print("ejemplo candidato:", cands[0]["text"]); print("ejemplo vacante:", jobs[1]["text"])
