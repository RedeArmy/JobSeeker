package com.usac.jobseeker.app.infrastructure.adapter.in.web;

import com.usac.jobseeker.app.infrastructure.security.CurrentActor;
import com.usac.jobseeker.common.domain.model.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Página protegida inicial: confirma quién ha iniciado sesión y con qué capacidades (BR-ACCT-01).
 */
@Controller
class HomeController {

  @GetMapping("/")
  String root() {
    return "redirect:/app/home";
  }

  @GetMapping("/app/home")
  String home(HttpServletRequest request, Model model) {
    // El ActorFilter ya resolvió quién actúa; sin actor no hay página.
    Actor actor = CurrentActor.from(request).orElseThrow();
    model.addAttribute("accountId", actor.id().toString());
    model.addAttribute("capabilities", actor.capabilities());
    return "app/home";
  }
}
