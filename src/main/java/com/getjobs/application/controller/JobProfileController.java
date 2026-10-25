package com.getjobs.application.controller;

import com.getjobs.application.entity.JobProfileEntity;
import com.getjobs.application.service.JobProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 岗位画像接口。 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/boss/profiles")
@RequiredArgsConstructor
public class JobProfileController {
    private final JobProfileService service;

    @GetMapping
    public List<JobProfileEntity> list() { return service.list(); }

    @GetMapping("/active")
    public JobProfileEntity active() { return service.getActive(); }

    @GetMapping("/{id}")
    public JobProfileEntity get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    public JobProfileEntity create(@RequestBody JobProfileEntity profile) { return service.save(profile); }

    @PutMapping("/{id}")
    public JobProfileEntity update(@PathVariable Long id,
                                   @RequestParam(defaultValue = "false") boolean replace,
                                   @RequestBody JobProfileEntity profile) {
        profile.setId(id);
        return service.update(profile, replace);
    }

    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) { return service.delete(id); }

    @PostMapping("/{id}/activate")
    public JobProfileEntity activate(@PathVariable Long id) { return service.activate(id); }
}
