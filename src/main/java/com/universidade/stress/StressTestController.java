package com.universidade.stress;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller do teste de elasticidade.
 *
 * Rotas:
 *   GET  /stress-test         → página HTML com painel de controle
 *   POST /stress-test/start   → inicia o teste
 *   POST /stress-test/stop    → para o teste
 *   GET  /stress-test/status  → retorna JSON (polling do JavaScript)
 */
@Controller
@RequestMapping("/stress-test")
@RequiredArgsConstructor
public class StressTestController {

    private final StressTestService stressTestService;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("status", stressTestService.getStatus());
        return "stress/test";
    }

    @PostMapping("/start")
    public String start(
            @RequestParam(defaultValue = "2") int threads,
            @RequestParam(defaultValue = "80") int cpuThreshold,
            RedirectAttributes ra) {

        stressTestService.start(threads, cpuThreshold);
        ra.addFlashAttribute("flashMessage", "Teste de elasticidade iniciado!");
        return "redirect:/stress-test";
    }

    @PostMapping("/stop")
    public String stop(RedirectAttributes ra) {
        stressTestService.stop();
        ra.addFlashAttribute("flashMessage", "Teste parado manualmente.");
        return "redirect:/stress-test";
    }

    /** Endpoint JSON para polling via JavaScript — atualiza o painel sem recarregar a página. */
    @GetMapping("/status")
    @ResponseBody
    public StressTestStatus status() {
        return stressTestService.getStatus();
    }
}
