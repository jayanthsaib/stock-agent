package com.jay.stagent.controller;

import com.jay.stagent.entity.AppUser;
import com.jay.stagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/users")
    public String usersPage(Model model) {
        model.addAttribute("users", userRepository.findAll());
        return "admin/users";
    }

    @PostMapping("/users/create")
    public String createUser(
            @RequestParam String username,
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam(defaultValue = "USER") String role,
            RedirectAttributes ra) {

        if (userRepository.existsByUsername(username)) {
            ra.addFlashAttribute("error", "Username '" + username + "' already exists.");
            return "redirect:/admin/users";
        }

        AppUser user = AppUser.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .active(true)
                .build();
        userRepository.save(user);
        ra.addFlashAttribute("success", "User '" + username + "' created successfully.");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/toggle")
    public String toggleUser(@PathVariable Long id, RedirectAttributes ra) {
        userRepository.findById(id).ifPresent(user -> {
            user.setActive(!user.isActive());
            userRepository.save(user);
            ra.addFlashAttribute("success",
                "User '" + user.getUsername() + "' " + (user.isActive() ? "enabled" : "disabled") + ".");
        });
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable Long id, RedirectAttributes ra) {
        userRepository.findById(id).ifPresent(user -> {
            if (user.getRole().equals("ADMIN")) {
                ra.addFlashAttribute("error", "Cannot delete admin account.");
                return;
            }
            userRepository.deleteById(id);
            ra.addFlashAttribute("success", "User '" + user.getUsername() + "' deleted.");
        });
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/reset-password")
    public String resetPassword(@PathVariable Long id,
                                @RequestParam String newPassword,
                                RedirectAttributes ra) {
        userRepository.findById(id).ifPresent(user -> {
            user.setPasswordHash(passwordEncoder.encode(newPassword));
            userRepository.save(user);
            ra.addFlashAttribute("success", "Password reset for '" + user.getUsername() + "'.");
        });
        return "redirect:/admin/users";
    }
}
