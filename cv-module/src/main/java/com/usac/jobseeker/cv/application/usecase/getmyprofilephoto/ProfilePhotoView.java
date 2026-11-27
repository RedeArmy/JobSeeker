package com.usac.jobseeker.cv.application.usecase.getmyprofilephoto;

/**
 * La foto tal como la ve su dueño, sin la clave del almacén.
 *
 * @param type `PNG` o `JPEG`
 * @param tag cambia con cada foto nueva; la pantalla lo añade a la URL para que el navegador no
 *     muestre la anterior desde su caché. No identifica a la persona
 */
public record ProfilePhotoView(String type, String tag) {}
