package ru.rtk.crm.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class AuthApiController {
    @GetMapping("/api/auth/login")
    public RedirectView login() {
        return new RedirectView("/api/auth/authorization/keycloak");
    }
}
