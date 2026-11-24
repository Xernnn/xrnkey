install(PROGRAMS xk-setup.sh DESTINATION bin RENAME xk-setup)
install(PROGRAMS xk-diagnose.sh DESTINATION bin RENAME xk-diagnose)
install(PROGRAMS xk-dict.py DESTINATION bin RENAME xk-dict)
install(PROGRAMS xk-macro.py DESTINATION bin RENAME xk-macro)
install(PROGRAMS xk-sync.py DESTINATION bin RENAME xk-sync)
# The personal words' sync with the phone, every hour once enabled: systemctl --user enable --now xk-sync.timer
install(FILES xk-sync.service xk-sync.timer DESTINATION lib/systemd/user)

if(XRNKEY_ENABLE_TESTS)
    find_package(Python3 COMPONENTS Interpreter)
    if(Python3_Interpreter_FOUND)
        add_test(NAME "cli: xk-macro"
                 COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/test_xk_macro.py)
        add_test(NAME "cli: xk-dict"
                 COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/test_xk_dict.py)
        add_test(NAME "cli: xk-sync"
                 COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/test_xk_sync.py)
        add_test(NAME "cli: xk-setup"
                 COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/test_xk_setup.py)
        add_test(NAME "cli: xk-diagnose"
                 COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/test_xk_diagnose.py)
    endif()
endif()
