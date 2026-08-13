package com.interview.agent.jd;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.exception.BaseException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JdService {
    private final JdMapper jdMapper;

    public JdService(JdMapper jdMapper) {
        this.jdMapper = jdMapper;
    }

    public Jd create(JdCreateDTO dto) {
        Jd jd = new Jd();
        jd.setUserId(BaseContext.getCurrentId());
        jd.setTitle(dto.getTitle());
        jd.setRawText(dto.getRawText());
        jd.setSourceUrl(dto.getSourceUrl());
        jdMapper.insert(jd);
        return jd;
    }

    public List<Jd> list() {
        return jdMapper.findByUserId(BaseContext.getCurrentId());
    }

    public Jd getById(Long id) {
        Jd jd = jdMapper.findById(id);
        if (jd == null) throw new BaseException("JD 不存在");
        if (!jd.getUserId().equals(BaseContext.getCurrentId())) {
            throw new BaseException("无权访问该 JD");
        }
        return jd;
    }

    public void delete(Long id) {
        int affected = jdMapper.deleteByIdAndUserId(id, BaseContext.getCurrentId());
        if (affected == 0) throw new BaseException("JD 不存在或无权删除");
    }

    /**
     * 编辑 JD（归属校验）
     */
    public Jd update(Long id, JdCreateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        Jd jd = jdMapper.findById(id);
        if (jd == null || !jd.getUserId().equals(userId)) {
            throw new BaseException("JD 不存在或无权修改");
        }
        jd.setTitle(dto.getTitle());
        jd.setRawText(dto.getRawText());
        jd.setSourceUrl(dto.getSourceUrl());
        int affected = jdMapper.update(jd);
        if (affected == 0) throw new BaseException("JD 不存在或无权修改");
        return jdMapper.findById(id);
    }
}
