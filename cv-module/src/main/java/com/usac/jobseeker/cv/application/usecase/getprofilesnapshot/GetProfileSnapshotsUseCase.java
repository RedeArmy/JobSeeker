package com.usac.jobseeker.cv.application.usecase.getprofilesnapshot;

import java.util.List;

/**
 * La misma lectura que {@link GetProfileSnapshotUseCase}, para varias cuentas con **pocas
 * consultas** y no una tanda por cuenta: los sugeridos puntúan hasta 200 recuperados y leerlos uno
 * a uno costaba unas 12 consultas por cuenta (TD-039). Es una interfaz aparte para no obligar a
 * quien simula la lectura individual a cambiar.
 */
public interface GetProfileSnapshotsUseCase {

  /**
   * Los perfiles legibles, **en el orden pedido**; las cuentas sin capacidad de candidato, sin
   * perfil o anonimizadas simplemente no aparecen. Aplica exactamente las mismas reglas que la
   * lectura individual, que es el caso de una cuenta.
   */
  List<ProfileSnapshot> execute(GetProfileSnapshotsQuery query);
}
