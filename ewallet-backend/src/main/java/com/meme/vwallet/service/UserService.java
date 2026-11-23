package com.meme.vwallet.service;

import com.meme.vwallet.entity.User;
import com.meme.vwallet.exception.InvalidPinException;
import com.meme.vwallet.exception.PinAlreadySetException;
import com.meme.vwallet.exception.PinNotSetException;
import com.meme.vwallet.exception.UserNotFoundException;
import com.meme.vwallet.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public void verifyPin(Long userId, String rawPin) {
        User user = findById(userId);
        if (!user.hasPin()) {
            throw new PinNotSetException("Tài khoản chưa cài đặt mã PIN giao dịch. Vui lòng thiết lập mã PIN trước khi thực hiện giao dịch.");
        }
        if (rawPin == null || !passwordEncoder.matches(rawPin, user.getPinHash())) {
            throw new InvalidPinException("Invalid PIN");
        }
    }

    @Transactional
    public void setPin(Long userId, String newPin) {
        User user = findById(userId);
        setPinInternal(user, newPin);
    }

    @Transactional
    public void setInitialPin(Long userId, String pin) {
        User user = findById(userId);
        if (user.hasPin()) {
            throw new PinAlreadySetException("Mã PIN giao dịch đã được thiết lập. Không thể cài đặt lại tại endpoint này.");
        }
        setPinInternal(user, pin);
    }

    private void setPinInternal(User user, String pin) {
        user.setPinHash(passwordEncoder.encode(pin));
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));
    }

    @Transactional(readOnly = true)
    public User findByPhoneNumber(String phoneNumber) {
        return userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new UserNotFoundException("User not found with phone number: " + phoneNumber));
    }
}
